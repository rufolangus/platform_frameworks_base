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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed store for human-in-the-loop consent decisions and an
 * audit log of every tool-call dispatched by {@link LlmManagerService}.
 *
 * <p>Lives at {@code /data/system/llm/llm_consent.db}.
 *
 * <p>Two responsibilities, two tables:
 * <ul>
 *   <li>{@code consent_grants} — persisted ALLOW/DENY decisions, keyed
 *       by (user_id, package_name, tool_name, scope, session_id). The
 *       package's signature hash is recorded at grant time; on lookup,
 *       a mismatch (app upgrade with new signature) invalidates the
 *       grant and forces re-prompt.</li>
 *   <li>{@code audit_calls} — every single tool-call event, with full
 *       args, result, status, consent decision, and duration. The user
 *       can review (forthcoming Settings UI) and revoke retroactively.</li>
 * </ul>
 *
 * <p>Thread-safe — all public methods are synchronized.
 */
public class HitlConsentStore extends SQLiteOpenHelper {

    private static final String TAG = "HitlConsentStore";

    private static final String DB_NAME = "llm_consent.db";
    private static final int DB_VERSION = 1;

    // ---- Decision constants (also exposed on android.llm.HitlDecision) ----
    public static final int DECISION_ALLOW = 1;
    public static final int DECISION_DENY  = 2;

    public static final int SCOPE_ONCE    = 0;  // cleared after use
    public static final int SCOPE_SESSION = 1;  // cleared at session end
    public static final int SCOPE_FOREVER = 2;  // persisted until revoke

    // Audit-only consent decision codes (recorded per tool call, never persisted as a grant)
    public static final int CONSENT_NONE          = 0; // tool didn't require confirmation
    public static final int CONSENT_AUTO          = 1; // matched a pre-existing grant
    public static final int CONSENT_ALLOWED_ONCE  = 2;
    public static final int CONSENT_ALLOWED_SESS  = 3;
    public static final int CONSENT_ALLOWED_FOREVER = 4;
    public static final int CONSENT_DENIED        = 5;
    public static final int CONSENT_TIMED_OUT     = 6;

    // ---- Schema ----
    private static final String SQL_CREATE_GRANTS =
            "CREATE TABLE consent_grants ("
            + "user_id        INTEGER NOT NULL,"
            + "package_name   TEXT    NOT NULL,"
            + "signature_hash TEXT    NOT NULL,"
            + "tool_name      TEXT    NOT NULL,"
            + "decision       INTEGER NOT NULL,"
            + "scope          INTEGER NOT NULL,"
            // SQLite forbids expressions in PRIMARY KEY (e.g. COALESCE),
            // so we normalize the nullable session_id to the empty
            // string at write/delete time and keep it NOT NULL here.
            + "session_id     TEXT    NOT NULL DEFAULT '',"
            + "granted_at     INTEGER NOT NULL,"
            + "PRIMARY KEY (user_id, package_name, tool_name, scope, session_id))";

    private static final String SQL_CREATE_GRANTS_INDEX =
            "CREATE INDEX consent_grants_lookup ON consent_grants "
            + "(user_id, package_name, tool_name)";

    private static final String SQL_CREATE_AUDIT =
            "CREATE TABLE audit_calls ("
            + "id               INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "user_id          INTEGER NOT NULL,"
            + "session_id       TEXT    NOT NULL,"
            + "package_name     TEXT    NOT NULL,"
            + "tool_name        TEXT    NOT NULL,"
            + "args_json        TEXT    NOT NULL,"
            + "result_json      TEXT,"
            + "status           INTEGER NOT NULL,"
            + "consent_decision INTEGER NOT NULL,"
            + "duration_ms      INTEGER NOT NULL DEFAULT 0,"
            + "iter_index       INTEGER NOT NULL DEFAULT 0,"
            + "ts               INTEGER NOT NULL)";

    private static final String SQL_CREATE_AUDIT_INDEX =
            "CREATE INDEX audit_lookup ON audit_calls (user_id, session_id, ts)";

    private final Context mContext;

    public HitlConsentStore(Context ctx) {
        // Stored alongside other system DBs in /data/system/llm/.
        super(ctx, DB_NAME, null, DB_VERSION);
        mContext = ctx;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_GRANTS);
        db.execSQL(SQL_CREATE_GRANTS_INDEX);
        db.execSQL(SQL_CREATE_AUDIT);
        db.execSQL(SQL_CREATE_AUDIT_INDEX);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v0.4 ships v1; future upgrades go here.
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        db.enableWriteAheadLogging();
    }

    // ===========================================================
    //  Consent lookup / record
    // ===========================================================

    /** Outcome of a {@link #check} call. */
    public enum Decision {
        /** A persisted ALLOW matches the request. Skip the prompt. */
        ALLOW,
        /** A persisted DENY exists. Don't prompt; the dispatcher should
         *  surface a "previously denied" error to the model. */
        DENY_PERSISTED,
        /** No persisted decision found. Prompt the user. */
        PROMPT
    }

    /**
     * Look up an existing grant for (userId, package, tool). Honors
     * scope: ONCE matches are deleted on read. SESSION matches require
     * the same sessionId. App-signature mismatch invalidates the
     * grant and returns PROMPT.
     */
    public synchronized Decision check(int userId, String pkg, String tool,
            String sessionId) {
        SQLiteDatabase db = getReadableDatabase();
        String sessionKey = sessionId != null ? sessionId : "";
        // Order: FOREVER first, then SESSION matching this sessionId, then ONCE.
        try (Cursor c = db.query("consent_grants",
                new String[]{"signature_hash", "decision", "scope", "session_id"},
                "user_id=? AND package_name=? AND tool_name=?",
                new String[]{String.valueOf(userId), pkg, tool},
                null, null, "scope DESC")) {
            String currentSig = currentSignatureHash(pkg);
            while (c.moveToNext()) {
                String sig = c.getString(0);
                int decision = c.getInt(1);
                int scope = c.getInt(2);
                String storedSessionId = c.getString(3);  // "" when unset

                if (currentSig != null && !currentSig.equals(sig)) {
                    // App was upgraded — grant no longer valid.
                    Log.i(TAG, "Signature mismatch on " + pkg + "/" + tool
                            + " — invalidating stale grant");
                    deleteGrant(db, userId, pkg, tool, scope, storedSessionId);
                    continue;
                }
                if (scope == SCOPE_SESSION
                        && !sessionKey.equals(storedSessionId)) {
                    continue;  // session-scoped, but different session
                }
                if (scope == SCOPE_ONCE) {
                    // Consume it.
                    deleteGrant(db, userId, pkg, tool, scope, storedSessionId);
                }
                return decision == DECISION_ALLOW ? Decision.ALLOW : Decision.DENY_PERSISTED;
            }
        }
        return Decision.PROMPT;
    }

    private void deleteGrant(SQLiteDatabase db, int userId, String pkg, String tool,
            int scope, String sessionId) {
        String key = sessionId != null ? sessionId : "";
        db.delete("consent_grants",
                "user_id=? AND package_name=? AND tool_name=? AND scope=? AND session_id=?",
                new String[]{String.valueOf(userId), pkg, tool,
                        String.valueOf(scope), key});
    }

    /** Persist a new grant. Caller is responsible for clamping scope. */
    public synchronized void record(int userId, String pkg, String tool,
            int decision, int scope, String sessionId) {
        ContentValues v = new ContentValues();
        v.put("user_id", userId);
        v.put("package_name", pkg);
        v.put("signature_hash", currentSignatureHash(pkg));
        v.put("tool_name", tool);
        v.put("decision", decision);
        v.put("scope", scope);
        // session_id is NOT NULL in the schema — normalize.
        v.put("session_id",
                scope == SCOPE_SESSION && sessionId != null ? sessionId : "");
        v.put("granted_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("consent_grants", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Revoke ALL grants (any scope) for (pkg, tool). */
    public synchronized void revoke(int userId, String pkg, String tool) {
        getWritableDatabase().delete("consent_grants",
                "user_id=? AND package_name=? AND tool_name=?",
                new String[]{String.valueOf(userId), pkg, tool});
    }

    /** Drop all SESSION-scoped grants for a given session (called on session end). */
    public synchronized void clearSessionScoped(String sessionId) {
        getWritableDatabase().delete("consent_grants",
                "scope=? AND session_id=?",
                new String[]{String.valueOf(SCOPE_SESSION), sessionId});
    }

    /** Drop all grants for an uninstalled package. */
    public synchronized void onPackageRemoved(String pkg) {
        getWritableDatabase().delete("consent_grants",
                "package_name=?", new String[]{pkg});
    }

    // ===========================================================
    //  Audit log
    // ===========================================================

    /** Append one audit entry for a tool call. Cheap, called per event. */
    public synchronized void recordAudit(int userId, String sessionId,
            String pkg, String tool, String argsJson, String resultJson,
            int status, int consentDecision, int durationMs, int iterIndex) {
        ContentValues v = new ContentValues();
        v.put("user_id", userId);
        v.put("session_id", sessionId);
        v.put("package_name", pkg);
        v.put("tool_name", tool);
        v.put("args_json", argsJson != null ? argsJson : "");
        v.put("result_json", resultJson);
        v.put("status", status);
        v.put("consent_decision", consentDecision);
        v.put("duration_ms", durationMs);
        v.put("iter_index", iterIndex);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insert("audit_calls", null, v);
    }

    /** Fetch the most recent audit entries, newest first, as JSON array. */
    public synchronized String recentCallsJson(int limit) {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().query("audit_calls",
                new String[]{"session_id", "package_name", "tool_name", "args_json",
                        "result_json", "status", "consent_decision", "duration_ms",
                        "iter_index", "ts"},
                null, null, null, null, "ts DESC", String.valueOf(Math.max(1, limit)))) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                try {
                    o.put("sessionId", c.getString(0));
                    o.put("packageName", c.getString(1));
                    o.put("toolName", c.getString(2));
                    o.put("argsJson", c.getString(3));
                    o.put("resultJson", c.getString(4));
                    o.put("status", c.getInt(5));
                    o.put("consentDecision", c.getInt(6));
                    o.put("durationMs", c.getInt(7));
                    o.put("iterIndex", c.getInt(8));
                    o.put("ts", c.getLong(9));
                    out.put(o);
                } catch (Exception ignored) {}
            }
        }
        return out.toString();
    }

    /** Plaintext lines for `dumpsys llm`. */
    public synchronized List<String> recentCallsPlain(int limit) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("audit_calls",
                new String[]{"ts", "package_name", "tool_name", "status",
                        "consent_decision", "duration_ms"},
                null, null, null, null, "ts DESC", String.valueOf(Math.max(1, limit)))) {
            while (c.moveToNext()) {
                out.add(String.format("[%d] %s/%s status=%d consent=%d dur=%dms",
                        c.getLong(0), c.getString(1), c.getString(2),
                        c.getInt(3), c.getInt(4), c.getInt(5)));
            }
        }
        return out;
    }

    // ===========================================================
    //  Signature hashing (for upgrade-invalidation)
    // ===========================================================

    private String currentSignatureHash(String pkg) {
        try {
            PackageInfo pi = mContext.getPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            if (pi.signatures == null || pi.signatures.length == 0) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : pi.signatures) {
                md.update(s.toByteArray());
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
