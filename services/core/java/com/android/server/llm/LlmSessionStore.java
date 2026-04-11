/*
 * Copyright (C) 2024 The AAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.llm;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQLite-backed persistent store for LLM sessions, messages, and tool stats.
 *
 * <p>Lives in {@code /data/system/llm/llm_sessions.db}. Used by
 * {@link LlmManagerService} to:
 * <ul>
 *   <li>Persist conversation history across launcher/service restarts</li>
 *   <li>Enable multi-turn sessions via session IDs</li>
 *   <li>Track tool call reliability (success rate, latency) for ranking</li>
 *   <li>Provide recent session listing for the launcher's history UI</li>
 * </ul>
 *
 * <h3>Retention policy</h3>
 * <ul>
 *   <li>Sessions older than 30 days are pruned on open</li>
 *   <li>Maximum 200 sessions retained</li>
 *   <li>Maximum 100 messages per session</li>
 *   <li>Tool stats are cumulative (never pruned, small fixed-size table)</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>All public methods are synchronized. The database itself uses WAL mode
 * for concurrent reads during writes. Called from the inference thread
 * (writes) and binder threads (reads via getSessionHistory/listSessions).
 */
public class LlmSessionStore extends SQLiteOpenHelper {

    private static final String TAG = "LlmSessionStore";

    private static final String DB_NAME = "llm_sessions.db";
    private static final int DB_VERSION = 1;

    // Retention limits
    private static final int MAX_SESSIONS = 200;
    private static final int MAX_MESSAGES_PER_SESSION = 100;
    private static final long RETENTION_DAYS = 30;
    private static final long RETENTION_MS = RETENTION_DAYS * 24 * 60 * 60 * 1000L;

    // -------------------------------------------------------------------------
    // Schema
    // -------------------------------------------------------------------------

    private static final String TABLE_SESSIONS = "sessions";
    private static final String TABLE_MESSAGES = "messages";
    private static final String TABLE_TOOL_STATS = "tool_stats";

    private static final String SQL_CREATE_SESSIONS =
            "CREATE TABLE " + TABLE_SESSIONS + " ("
            + "session_id TEXT PRIMARY KEY,"
            + "uid INTEGER NOT NULL,"
            + "created_at INTEGER NOT NULL,"
            + "updated_at INTEGER NOT NULL,"
            + "title TEXT,"
            + "message_count INTEGER NOT NULL DEFAULT 0"
            + ")";

    private static final String SQL_CREATE_MESSAGES =
            "CREATE TABLE " + TABLE_MESSAGES + " ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "session_id TEXT NOT NULL,"
            + "role TEXT NOT NULL,"           // user, assistant, tool
            + "content TEXT NOT NULL,"
            + "tool_name TEXT,"               // non-null for tool role
            + "tool_args_json TEXT,"          // tool call arguments
            + "tool_result_json TEXT,"        // tool call result
            + "created_at INTEGER NOT NULL,"
            + "FOREIGN KEY (session_id) REFERENCES "
            +     TABLE_SESSIONS + "(session_id) ON DELETE CASCADE"
            + ")";

    private static final String SQL_CREATE_TOOL_STATS =
            "CREATE TABLE " + TABLE_TOOL_STATS + " ("
            + "tool_name TEXT PRIMARY KEY,"
            + "package_name TEXT NOT NULL,"
            + "call_count INTEGER NOT NULL DEFAULT 0,"
            + "success_count INTEGER NOT NULL DEFAULT 0,"
            + "error_count INTEGER NOT NULL DEFAULT 0,"
            + "total_latency_ms INTEGER NOT NULL DEFAULT 0,"
            + "last_called_at INTEGER NOT NULL DEFAULT 0,"
            + "last_error TEXT"
            + ")";

    // Indexes for common queries
    private static final String SQL_IDX_MESSAGES_SESSION =
            "CREATE INDEX idx_messages_session ON "
            + TABLE_MESSAGES + "(session_id, created_at ASC)";

    private static final String SQL_IDX_SESSIONS_UPDATED =
            "CREATE INDEX idx_sessions_updated ON "
            + TABLE_SESSIONS + "(updated_at DESC)";

    private static final String SQL_IDX_SESSIONS_UID =
            "CREATE INDEX idx_sessions_uid ON "
            + TABLE_SESSIONS + "(uid, updated_at DESC)";

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public LlmSessionStore(Context context) {
        // Store in /data/system/llm/ — system_server's private data dir
        super(context.createDeviceProtectedStorageContext(),
                DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_SESSIONS);
        db.execSQL(SQL_CREATE_MESSAGES);
        db.execSQL(SQL_CREATE_TOOL_STATS);
        db.execSQL(SQL_IDX_MESSAGES_SESSION);
        db.execSQL(SQL_IDX_SESSIONS_UPDATED);
        db.execSQL(SQL_IDX_SESSIONS_UID);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future schema migrations go here.
        // Pattern: if (oldVersion < 2) { ... migrate to v2 ... }
        Log.i(TAG, "Upgrading DB from v" + oldVersion + " to v" + newVersion);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        // WAL mode for concurrent reads during writes
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // Prune old sessions on open (non-blocking, fast with index)
        pruneOldSessions(db);
    }

    // -------------------------------------------------------------------------
    // Session operations
    // -------------------------------------------------------------------------

    /**
     * Create a new session. Returns the session ID.
     */
    public synchronized String createSession(String sessionId, int uid) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("session_id", sessionId);
        values.put("uid", uid);
        values.put("created_at", now);
        values.put("updated_at", now);

        getWritableDatabase().insertWithOnConflict(
                TABLE_SESSIONS, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);

        Log.d(TAG, "Created session " + sessionId + " for uid " + uid);
        return sessionId;
    }

    /**
     * Update the session title (auto-generated from first user message).
     */
    public synchronized void updateSessionTitle(String sessionId, String title) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(TABLE_SESSIONS, values,
                "session_id = ?", new String[]{sessionId});
    }

    /**
     * List recent sessions for a caller UID, newest first.
     *
     * @param uid the calling app's UID
     * @param limit max number of sessions to return
     * @return list of session summaries
     */
    public synchronized List<SessionSummary> listSessions(int uid, int limit) {
        List<SessionSummary> sessions = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        try (Cursor cursor = db.query(
                TABLE_SESSIONS,
                new String[]{"session_id", "title", "created_at",
                        "updated_at", "message_count"},
                "uid = ?",
                new String[]{String.valueOf(uid)},
                null, null,
                "updated_at DESC",
                String.valueOf(limit))) {

            while (cursor.moveToNext()) {
                sessions.add(new SessionSummary(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getLong(2),
                        cursor.getLong(3),
                        cursor.getInt(4)));
            }
        }
        return sessions;
    }

    /**
     * Delete a session and all its messages (CASCADE).
     */
    public synchronized void deleteSession(String sessionId, int uid) {
        int deleted = getWritableDatabase().delete(TABLE_SESSIONS,
                "session_id = ? AND uid = ?",
                new String[]{sessionId, String.valueOf(uid)});
        if (deleted > 0) {
            Log.i(TAG, "Deleted session " + sessionId);
        }
    }

    // -------------------------------------------------------------------------
    // Message operations
    // -------------------------------------------------------------------------

    /**
     * Add a user or assistant message to a session.
     */
    public synchronized void addMessage(String sessionId, String role,
            String content) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("session_id", sessionId);
        values.put("role", role);
        values.put("content", content);
        values.put("created_at", now);
        db.insert(TABLE_MESSAGES, null, values);

        // Update session timestamp and message count
        db.execSQL("UPDATE " + TABLE_SESSIONS
                + " SET updated_at = ?, message_count = message_count + 1"
                + " WHERE session_id = ?",
                new Object[]{now, sessionId});

        // Auto-set title from first user message
        if ("user".equals(role)) {
            db.execSQL("UPDATE " + TABLE_SESSIONS
                    + " SET title = ? WHERE session_id = ? AND title IS NULL",
                    new Object[]{truncateTitle(content), sessionId});
        }

        // Cap messages per session
        trimMessages(db, sessionId);
    }

    /**
     * Add a tool call message with arguments and result.
     */
    public synchronized void addToolMessage(String sessionId, String toolName,
            String argsJson, String resultJson) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("session_id", sessionId);
        values.put("role", "tool");
        values.put("content", resultJson != null ? resultJson : "");
        values.put("tool_name", toolName);
        values.put("tool_args_json", argsJson);
        values.put("tool_result_json", resultJson);
        values.put("created_at", now);
        db.insert(TABLE_MESSAGES, null, values);

        db.execSQL("UPDATE " + TABLE_SESSIONS
                + " SET updated_at = ?, message_count = message_count + 1"
                + " WHERE session_id = ?",
                new Object[]{now, sessionId});
    }

    /**
     * Get conversation history for a session, ordered chronologically.
     * Used to rebuild the prompt for multi-turn conversations.
     *
     * @param sessionId the session
     * @param limit max messages to return (most recent)
     * @return messages in chronological order
     */
    public synchronized List<StoredMessage> getSessionHistory(
            String sessionId, int limit) {
        List<StoredMessage> messages = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        // Get the most recent N messages, then reverse for chronological order
        try (Cursor cursor = db.query(
                TABLE_MESSAGES,
                new String[]{"role", "content", "tool_name",
                        "tool_args_json", "tool_result_json", "created_at"},
                "session_id = ?",
                new String[]{sessionId},
                null, null,
                "created_at DESC",
                String.valueOf(limit))) {

            while (cursor.moveToNext()) {
                messages.add(new StoredMessage(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getLong(5)));
            }
        }

        Collections.reverse(messages);
        return messages;
    }

    // -------------------------------------------------------------------------
    // Tool statistics
    // -------------------------------------------------------------------------

    /**
     * Record a successful tool call.
     */
    public synchronized void recordToolSuccess(String toolName,
            String packageName, long latencyMs) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();

        db.execSQL("INSERT INTO " + TABLE_TOOL_STATS
                + " (tool_name, package_name, call_count, success_count,"
                + "  error_count, total_latency_ms, last_called_at)"
                + " VALUES (?, ?, 1, 1, 0, ?, ?)"
                + " ON CONFLICT(tool_name) DO UPDATE SET"
                + "  call_count = call_count + 1,"
                + "  success_count = success_count + 1,"
                + "  total_latency_ms = total_latency_ms + ?,"
                + "  last_called_at = ?",
                new Object[]{toolName, packageName, latencyMs, now,
                        latencyMs, now});
    }

    /**
     * Record a failed tool call.
     */
    public synchronized void recordToolError(String toolName,
            String packageName, String error) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();

        db.execSQL("INSERT INTO " + TABLE_TOOL_STATS
                + " (tool_name, package_name, call_count, success_count,"
                + "  error_count, total_latency_ms, last_called_at, last_error)"
                + " VALUES (?, ?, 1, 0, 1, 0, ?, ?)"
                + " ON CONFLICT(tool_name) DO UPDATE SET"
                + "  call_count = call_count + 1,"
                + "  error_count = error_count + 1,"
                + "  last_called_at = ?,"
                + "  last_error = ?",
                new Object[]{toolName, packageName, now, error, now, error});
    }

    /**
     * Get stats for all tools, ordered by success rate descending.
     * Used by the service to rank tools in the prompt.
     */
    public synchronized List<ToolStats> getAllToolStats() {
        List<ToolStats> stats = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        try (Cursor cursor = db.rawQuery(
                "SELECT tool_name, package_name, call_count, success_count,"
                + " error_count, total_latency_ms, last_called_at, last_error"
                + " FROM " + TABLE_TOOL_STATS
                + " ORDER BY CAST(success_count AS REAL) / MAX(call_count, 1) DESC,"
                + "          call_count DESC",
                null)) {

            while (cursor.moveToNext()) {
                stats.add(new ToolStats(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getInt(2),
                        cursor.getInt(3),
                        cursor.getInt(4),
                        cursor.getLong(5),
                        cursor.getLong(6),
                        cursor.getString(7)));
            }
        }
        return stats;
    }

    /**
     * Get stats for a specific tool.
     */
    public synchronized ToolStats getToolStats(String toolName) {
        SQLiteDatabase db = getReadableDatabase();

        try (Cursor cursor = db.query(
                TABLE_TOOL_STATS,
                new String[]{"tool_name", "package_name", "call_count",
                        "success_count", "error_count", "total_latency_ms",
                        "last_called_at", "last_error"},
                "tool_name = ?",
                new String[]{toolName},
                null, null, null)) {

            if (cursor.moveToFirst()) {
                return new ToolStats(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getInt(2),
                        cursor.getInt(3),
                        cursor.getInt(4),
                        cursor.getLong(5),
                        cursor.getLong(6),
                        cursor.getString(7));
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Maintenance
    // -------------------------------------------------------------------------

    private void pruneOldSessions(SQLiteDatabase db) {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;

        // Delete sessions older than retention period
        int deleted = db.delete(TABLE_SESSIONS,
                "updated_at < ?",
                new String[]{String.valueOf(cutoff)});

        if (deleted > 0) {
            Log.i(TAG, "Pruned " + deleted + " expired sessions");
        }

        // If still over limit, delete oldest
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_SESSIONS, null)) {
            if (cursor.moveToFirst() && cursor.getInt(0) > MAX_SESSIONS) {
                int excess = cursor.getInt(0) - MAX_SESSIONS;
                db.execSQL("DELETE FROM " + TABLE_SESSIONS
                        + " WHERE session_id IN ("
                        + "  SELECT session_id FROM " + TABLE_SESSIONS
                        + "  ORDER BY updated_at ASC LIMIT " + excess + ")");
                Log.i(TAG, "Pruned " + excess + " sessions over limit");
            }
        }
    }

    private void trimMessages(SQLiteDatabase db, String sessionId) {
        // Count messages in session
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_MESSAGES
                + " WHERE session_id = ?",
                new String[]{sessionId})) {
            if (cursor.moveToFirst()
                    && cursor.getInt(0) > MAX_MESSAGES_PER_SESSION) {
                int excess = cursor.getInt(0) - MAX_MESSAGES_PER_SESSION;
                db.execSQL("DELETE FROM " + TABLE_MESSAGES
                        + " WHERE id IN ("
                        + "  SELECT id FROM " + TABLE_MESSAGES
                        + "  WHERE session_id = ?"
                        + "  ORDER BY created_at ASC LIMIT " + excess + ")",
                        new Object[]{sessionId});
            }
        }
    }

    private static String truncateTitle(String content) {
        if (content == null) return null;
        String title = content.trim();
        // First line only, max 80 chars
        int newline = title.indexOf('\n');
        if (newline > 0) title = title.substring(0, newline);
        if (title.length() > 80) title = title.substring(0, 77) + "...";
        return title;
    }

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    /** Summary of a session for listing. */
    public static class SessionSummary {
        public final String sessionId;
        public final String title;
        public final long createdAt;
        public final long updatedAt;
        public final int messageCount;

        SessionSummary(String sessionId, String title, long createdAt,
                long updatedAt, int messageCount) {
            this.sessionId = sessionId;
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.messageCount = messageCount;
        }
    }

    /** A persisted message. */
    public static class StoredMessage {
        public final String role;
        public final String content;
        public final String toolName;
        public final String toolArgsJson;
        public final String toolResultJson;
        public final long createdAt;

        StoredMessage(String role, String content, String toolName,
                String toolArgsJson, String toolResultJson, long createdAt) {
            this.role = role;
            this.content = content;
            this.toolName = toolName;
            this.toolArgsJson = toolArgsJson;
            this.toolResultJson = toolResultJson;
            this.createdAt = createdAt;
        }

        public boolean isToolCall() {
            return "tool".equals(role) && toolName != null;
        }
    }

    /** Cumulative stats for a tool. */
    public static class ToolStats {
        public final String toolName;
        public final String packageName;
        public final int callCount;
        public final int successCount;
        public final int errorCount;
        public final long totalLatencyMs;
        public final long lastCalledAt;
        public final String lastError;

        ToolStats(String toolName, String packageName, int callCount,
                int successCount, int errorCount, long totalLatencyMs,
                long lastCalledAt, String lastError) {
            this.toolName = toolName;
            this.packageName = packageName;
            this.callCount = callCount;
            this.successCount = successCount;
            this.errorCount = errorCount;
            this.totalLatencyMs = totalLatencyMs;
            this.lastCalledAt = lastCalledAt;
            this.lastError = lastError;
        }

        /** Success rate as a fraction (0.0 to 1.0). */
        public float successRate() {
            return callCount > 0 ? (float) successCount / callCount : 0f;
        }

        /** Average latency per successful call in ms. */
        public long avgLatencyMs() {
            return successCount > 0 ? totalLatencyMs / successCount : 0;
        }

        @Override
        public String toString() {
            return "ToolStats{" + toolName
                    + ", calls=" + callCount
                    + ", success=" + successRate() * 100 + "%"
                    + ", avgMs=" + avgLatencyMs() + "}";
        }
    }
}
