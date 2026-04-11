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
import java.util.List;

/**
 * Manages user consent for MCP tool access and audit trail.
 *
 * <h3>Layer 1: MCP Server Access (first-use consent)</h3>
 * <p>Before the LLM can use any tool from an app, the user must grant
 * access. One-time per app, persisted, revocable in Settings.
 *
 * <h3>Layer 2: Tool Confirmation (per-call for destructive actions)</h3>
 * <p>Tools with {@code mcpRequiresConfirmation="true"} show a confirmation
 * dialog. Users can check "Don't ask again for this action" to auto-confirm
 * future calls to that specific tool. Auto-confirms are per-tool, per-user,
 * and revocable in Settings.
 *
 * <h3>Layer 3: Audit Trail</h3>
 * <p>Every tool invocation is logged. Users can review in Settings.
 */
public class McpConsentManager extends SQLiteOpenHelper {

    private static final String TAG = "McpConsentManager";

    private static final String DB_NAME = "mcp_consent.db";
    private static final int DB_VERSION = 2;

    public static final int CONSENT_NOT_ASKED = 0;
    public static final int CONSENT_GRANTED = 1;
    public static final int CONSENT_DENIED = 2;

    private static final String TABLE_CONSENT = "mcp_consent";
    private static final String TABLE_AUTO_CONFIRM = "auto_confirm";
    private static final String TABLE_AUDIT = "tool_audit";

    private static final String SQL_CREATE_CONSENT =
            "CREATE TABLE " + TABLE_CONSENT + " ("
            + "package_name TEXT NOT NULL,"
            + "user_id INTEGER NOT NULL,"
            + "consent INTEGER NOT NULL DEFAULT " + CONSENT_NOT_ASKED + ","
            + "granted_at INTEGER,"
            + "denied_at INTEGER,"
            + "tool_count INTEGER NOT NULL DEFAULT 0,"
            + "description TEXT,"
            + "PRIMARY KEY (package_name, user_id)"
            + ")";

    /** Per-tool auto-confirm: user chose "don't ask again" */
    private static final String SQL_CREATE_AUTO_CONFIRM =
            "CREATE TABLE " + TABLE_AUTO_CONFIRM + " ("
            + "package_name TEXT NOT NULL,"
            + "tool_name TEXT NOT NULL,"
            + "user_id INTEGER NOT NULL,"
            + "created_at INTEGER NOT NULL,"
            + "PRIMARY KEY (package_name, tool_name, user_id)"
            + ")";

    private static final String SQL_CREATE_AUDIT =
            "CREATE TABLE " + TABLE_AUDIT + " ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "timestamp INTEGER NOT NULL,"
            + "user_id INTEGER NOT NULL,"
            + "package_name TEXT NOT NULL,"
            + "tool_name TEXT NOT NULL,"
            + "args_summary TEXT,"
            + "result_summary TEXT,"
            + "required_confirmation INTEGER NOT NULL DEFAULT 0,"
            + "user_confirmed INTEGER NOT NULL DEFAULT 0,"
            + "auto_confirmed INTEGER NOT NULL DEFAULT 0,"
            + "success INTEGER NOT NULL DEFAULT 1,"
            + "latency_ms INTEGER NOT NULL DEFAULT 0"
            + ")";

    private static final String SQL_IDX_AUDIT =
            "CREATE INDEX idx_audit_time ON "
            + TABLE_AUDIT + "(user_id, timestamp DESC)";

    public McpConsentManager(Context context) {
        super(context.createDeviceProtectedStorageContext(),
                DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_CONSENT);
        db.execSQL(SQL_CREATE_AUTO_CONFIRM);
        db.execSQL(SQL_CREATE_AUDIT);
        db.execSQL(SQL_IDX_AUDIT);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL(SQL_CREATE_AUTO_CONFIRM);
            db.execSQL("ALTER TABLE " + TABLE_AUDIT
                    + " ADD COLUMN auto_confirmed INTEGER NOT NULL DEFAULT 0");
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
    }

    // ------------------------------------------------------------------
    // Layer 1: MCP Server Access Consent
    // ------------------------------------------------------------------

    public synchronized int getConsent(String packageName, int userId) {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_CONSENT, new String[]{"consent"},
                "package_name = ? AND user_id = ?",
                new String[]{packageName, String.valueOf(userId)},
                null, null, null)) {
            if (cursor.moveToFirst()) return cursor.getInt(0);
        }
        return CONSENT_NOT_ASKED;
    }

    public boolean isAllowed(String packageName, int userId) {
        return getConsent(packageName, userId) == CONSENT_GRANTED;
    }

    public synchronized void setConsent(String packageName, int userId,
            boolean granted, String description, int toolCount) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("package_name", packageName);
        values.put("user_id", userId);
        values.put("consent", granted ? CONSENT_GRANTED : CONSENT_DENIED);
        values.put("description", description);
        values.put("tool_count", toolCount);
        if (granted) values.put("granted_at", now);
        else values.put("denied_at", now);

        getWritableDatabase().insertWithOnConflict(
                TABLE_CONSENT, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);

        // If consent revoked, also clear all auto-confirms for this package
        if (!granted) {
            clearAutoConfirms(packageName, userId);
        }

        Log.i(TAG, "Consent " + (granted ? "GRANTED" : "DENIED")
                + " for " + packageName + " user=" + userId);
    }

    public synchronized void revokeConsent(String packageName, int userId) {
        setConsent(packageName, userId, false, null, 0);
    }

    public synchronized List<ConsentRecord> getAllConsent(int userId) {
        List<ConsentRecord> records = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_CONSENT,
                new String[]{"package_name", "consent", "granted_at",
                        "denied_at", "tool_count", "description"},
                "user_id = ?",
                new String[]{String.valueOf(userId)},
                null, null, "package_name ASC")) {
            while (cursor.moveToNext()) {
                records.add(new ConsentRecord(
                        cursor.getString(0), cursor.getInt(1),
                        cursor.getLong(2), cursor.getLong(3),
                        cursor.getInt(4), cursor.getString(5)));
            }
        }
        return records;
    }

    public synchronized void onPackageRemoved(String packageName) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_CONSENT, "package_name = ?",
                new String[]{packageName});
        db.delete(TABLE_AUTO_CONFIRM, "package_name = ?",
                new String[]{packageName});
    }

    // ------------------------------------------------------------------
    // Layer 2: "Don't ask again" per-tool auto-confirm
    // ------------------------------------------------------------------

    /**
     * Check if the user has chosen "don't ask again" for a specific tool.
     */
    public synchronized boolean isAutoConfirmed(String packageName,
            String toolName, int userId) {
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_AUTO_CONFIRM, new String[]{"1"},
                "package_name = ? AND tool_name = ? AND user_id = ?",
                new String[]{packageName, toolName, String.valueOf(userId)},
                null, null, null)) {
            return cursor.moveToFirst();
        }
    }

    /**
     * Set "don't ask again" for a specific tool.
     * Called when user checks the box in the confirmation dialog.
     */
    public synchronized void setAutoConfirm(String packageName,
            String toolName, int userId) {
        ContentValues values = new ContentValues();
        values.put("package_name", packageName);
        values.put("tool_name", toolName);
        values.put("user_id", userId);
        values.put("created_at", System.currentTimeMillis());

        getWritableDatabase().insertWithOnConflict(
                TABLE_AUTO_CONFIRM, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);

        Log.i(TAG, "Auto-confirm set for " + packageName + "/" + toolName);
    }

    /**
     * Remove auto-confirm for a specific tool. Used from Settings.
     */
    public synchronized void removeAutoConfirm(String packageName,
            String toolName, int userId) {
        getWritableDatabase().delete(TABLE_AUTO_CONFIRM,
                "package_name = ? AND tool_name = ? AND user_id = ?",
                new String[]{packageName, toolName, String.valueOf(userId)});
    }

    /**
     * Clear all auto-confirms for a package (when consent is revoked).
     */
    private void clearAutoConfirms(String packageName, int userId) {
        getWritableDatabase().delete(TABLE_AUTO_CONFIRM,
                "package_name = ? AND user_id = ?",
                new String[]{packageName, String.valueOf(userId)});
    }

    /**
     * Get all auto-confirmed tools for a user. Used by Settings UI.
     */
    public synchronized List<AutoConfirmRecord> getAllAutoConfirms(
            int userId) {
        List<AutoConfirmRecord> records = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_AUTO_CONFIRM,
                new String[]{"package_name", "tool_name", "created_at"},
                "user_id = ?",
                new String[]{String.valueOf(userId)},
                null, null, "package_name ASC, tool_name ASC")) {
            while (cursor.moveToNext()) {
                records.add(new AutoConfirmRecord(
                        cursor.getString(0), cursor.getString(1),
                        cursor.getLong(2)));
            }
        }
        return records;
    }

    // ------------------------------------------------------------------
    // Layer 3: Audit Trail
    // ------------------------------------------------------------------

    public synchronized void logToolCall(int userId, String packageName,
            String toolName, String argsSummary, String resultSummary,
            boolean requiredConfirmation, boolean userConfirmed,
            boolean autoConfirmed, boolean success, long latencyMs) {
        ContentValues values = new ContentValues();
        values.put("timestamp", System.currentTimeMillis());
        values.put("user_id", userId);
        values.put("package_name", packageName);
        values.put("tool_name", toolName);
        values.put("args_summary", truncate(argsSummary, 200));
        values.put("result_summary", truncate(resultSummary, 200));
        values.put("required_confirmation", requiredConfirmation ? 1 : 0);
        values.put("user_confirmed", userConfirmed ? 1 : 0);
        values.put("auto_confirmed", autoConfirmed ? 1 : 0);
        values.put("success", success ? 1 : 0);
        values.put("latency_ms", latencyMs);

        getWritableDatabase().insert(TABLE_AUDIT, null, values);

        // Cap at 1000 entries per user.
        // Only prune if over limit (avoid expensive query on every insert).
        // The WHERE user_id=? on the outer DELETE is critical — without it
        // we'd delete other users' rows.
        try (Cursor count = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_AUDIT + " WHERE user_id = ?",
                new String[]{String.valueOf(userId)})) {
            if (count.moveToFirst() && count.getInt(0) > 1000) {
                getWritableDatabase().execSQL(
                        "DELETE FROM " + TABLE_AUDIT
                        + " WHERE user_id = ? AND id NOT IN ("
                        + "  SELECT id FROM " + TABLE_AUDIT
                        + "  WHERE user_id = ?"
                        + "  ORDER BY timestamp DESC LIMIT 1000"
                        + ")", new Object[]{userId, userId});
            }
        }
    }

    public synchronized List<AuditEntry> getToolActivity(
            int userId, int limit) {
        List<AuditEntry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_AUDIT,
                new String[]{"timestamp", "package_name", "tool_name",
                        "args_summary", "result_summary",
                        "required_confirmation", "user_confirmed",
                        "auto_confirmed", "success", "latency_ms"},
                "user_id = ?",
                new String[]{String.valueOf(userId)},
                null, null, "timestamp DESC",
                String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                entries.add(new AuditEntry(
                        cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3),
                        cursor.getString(4), cursor.getInt(5) == 1,
                        cursor.getInt(6) == 1, cursor.getInt(7) == 1,
                        cursor.getInt(8) == 1, cursor.getLong(9)));
            }
        }
        return entries;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // ------------------------------------------------------------------
    // Data classes
    // ------------------------------------------------------------------

    public static class ConsentRecord {
        public final String packageName;
        public final int consent;
        public final long grantedAt;
        public final long deniedAt;
        public final int toolCount;
        public final String description;

        ConsentRecord(String packageName, int consent, long grantedAt,
                long deniedAt, int toolCount, String description) {
            this.packageName = packageName;
            this.consent = consent;
            this.grantedAt = grantedAt;
            this.deniedAt = deniedAt;
            this.toolCount = toolCount;
            this.description = description;
        }

        public boolean isGranted() {
            return consent == CONSENT_GRANTED;
        }
    }

    public static class AutoConfirmRecord {
        public final String packageName;
        public final String toolName;
        public final long createdAt;

        AutoConfirmRecord(String packageName, String toolName,
                long createdAt) {
            this.packageName = packageName;
            this.toolName = toolName;
            this.createdAt = createdAt;
        }
    }

    public static class AuditEntry {
        public final long timestamp;
        public final String packageName;
        public final String toolName;
        public final String argsSummary;
        public final String resultSummary;
        public final boolean requiredConfirmation;
        public final boolean userConfirmed;
        public final boolean autoConfirmed;
        public final boolean success;
        public final long latencyMs;

        AuditEntry(long timestamp, String packageName, String toolName,
                String argsSummary, String resultSummary,
                boolean requiredConfirmation, boolean userConfirmed,
                boolean autoConfirmed, boolean success, long latencyMs) {
            this.timestamp = timestamp;
            this.packageName = packageName;
            this.toolName = toolName;
            this.argsSummary = argsSummary;
            this.resultSummary = resultSummary;
            this.requiredConfirmation = requiredConfirmation;
            this.userConfirmed = userConfirmed;
            this.autoConfirmed = autoConfirmed;
            this.success = success;
            this.latencyMs = latencyMs;
        }
    }
}
