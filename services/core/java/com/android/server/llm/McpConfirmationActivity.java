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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.TypedValue;

/**
 * System activity that shows human-in-the-loop consent and confirmation
 * dialogs for MCP tool calls.
 *
 * <p>Two dialog types:
 *
 * <p><b>TYPE_CONSENT</b>: "Allow AI to use Contacts?"
 * <br>Shown once per MCP server on first tool access.
 *
 * <p><b>TYPE_CONFIRM</b>: "Send message to John?"
 * <br>Shown for tools with mcpRequiresConfirmation=true.
 * <br>Includes "Don't ask again for this action" checkbox.
 *
 * <p>Returns result via {@link ResultReceiver} passed in intent extras.
 * Uses RESULT_GRANTED (1), RESULT_DENIED (0), or RESULT_AUTO_CONFIRM (2)
 * when user checks "don't ask again" and confirms.
 *
 * <p><b>Threading:</b> The ResultReceiver MUST be created with a Handler
 * on a thread OTHER than the one waiting for the result. The calling
 * service should use a dedicated Handler or the main looper.
 */
public class McpConfirmationActivity extends Activity {

    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_PACKAGE_NAME = "package_name";
    public static final String EXTRA_APP_LABEL = "app_label";
    public static final String EXTRA_DESCRIPTION = "description";
    public static final String EXTRA_TOOL_NAMES = "tool_names";
    public static final String EXTRA_TOOL_NAME = "tool_name";
    public static final String EXTRA_ACTION_SUMMARY = "action_summary";
    public static final String EXTRA_RESULT_RECEIVER = "result_receiver";

    public static final int TYPE_CONSENT = 1;
    public static final int TYPE_CONFIRM = 2;

    public static final int RESULT_DENIED = 0;
    public static final int RESULT_GRANTED = 1;
    /** User confirmed AND checked "don't ask again". */
    public static final int RESULT_AUTO_CONFIRM = 2;

    private ResultReceiver mResultReceiver;
    private boolean mResultSent = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        int type = intent.getIntExtra(EXTRA_TYPE, 0);
        mResultReceiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);

        if (mResultReceiver == null) {
            finish();
            return;
        }

        switch (type) {
            case TYPE_CONSENT:
                showConsentDialog(intent);
                break;
            case TYPE_CONFIRM:
                showConfirmDialog(intent);
                break;
            default:
                sendResult(RESULT_DENIED);
                finish();
        }
    }

    private void showConsentDialog(Intent intent) {
        String appLabel = intent.getStringExtra(EXTRA_APP_LABEL);
        String description = intent.getStringExtra(EXTRA_DESCRIPTION);
        String[] toolNames = intent.getStringArrayExtra(EXTRA_TOOL_NAMES);

        StringBuilder message = new StringBuilder();
        if (description != null && !description.isEmpty()) {
            message.append(description).append("\n\n");
        }
        if (toolNames != null && toolNames.length > 0) {
            message.append("This app's AI tools:\n");
            for (String tool : toolNames) {
                message.append("\u2022 ").append(humanize(tool)).append("\n");
            }
            message.append("\n");
        }
        message.append("The AI assistant will be able to use these tools ")
                .append("when you ask questions. You can change this anytime ")
                .append("in Settings \u2192 AI \u2192 Tool Access.");

        new AlertDialog.Builder(this)
                .setTitle("Allow AI to use " + (appLabel != null ? appLabel : "this app") + "?")
                .setMessage(message.toString())
                .setPositiveButton("Allow", (dialog, which) -> {
                    sendResult(RESULT_GRANTED);
                    finish();
                })
                .setNegativeButton("Don\u2019t Allow", (dialog, which) -> {
                    sendResult(RESULT_DENIED);
                    finish();
                })
                .setOnCancelListener(dialog -> {
                    sendResult(RESULT_DENIED);
                    finish();
                })
                .setCancelable(true)
                .show();
    }

    private void showConfirmDialog(Intent intent) {
        String toolName = intent.getStringExtra(EXTRA_TOOL_NAME);
        String actionSummary = intent.getStringExtra(EXTRA_ACTION_SUMMARY);
        String appLabel = intent.getStringExtra(EXTRA_APP_LABEL);

        String title = humanize(toolName) + "?";

        // Build custom view with message + "don't ask again" checkbox
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24,
                getResources().getDisplayMetrics());
        int padSmall = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8,
                getResources().getDisplayMetrics());
        layout.setPadding(pad, padSmall, pad, 0);

        // Action summary text
        TextView messageView = new TextView(this);
        messageView.setText(actionSummary != null
                ? actionSummary
                : "The AI wants to use " + humanize(toolName)
                        + " via " + (appLabel != null ? appLabel : "an app") + ".");
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        layout.addView(messageView);

        // "Don't ask again" checkbox
        CheckBox dontAskAgain = new CheckBox(this);
        dontAskAgain.setText("Don\u2019t ask again for this action");
        dontAskAgain.setPadding(0, pad, 0, 0);
        layout.addView(dontAskAgain);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton("Confirm", (dialog, which) -> {
                    if (dontAskAgain.isChecked()) {
                        sendResult(RESULT_AUTO_CONFIRM);
                    } else {
                        sendResult(RESULT_GRANTED);
                    }
                    finish();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    sendResult(RESULT_DENIED);
                    finish();
                })
                .setOnCancelListener(dialog -> {
                    sendResult(RESULT_DENIED);
                    finish();
                })
                .setCancelable(true)
                .show();
    }

    private void sendResult(int result) {
        if (!mResultSent && mResultReceiver != null) {
            mResultSent = true;
            mResultReceiver.send(result, null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sendResult(RESULT_DENIED);
    }

    /** Convert "search_contacts" to "Search contacts". */
    private static String humanize(String name) {
        if (name == null || name.isEmpty()) return "Unknown";
        String readable = name.replace('_', ' ');
        return Character.toUpperCase(readable.charAt(0))
                + readable.substring(1);
    }
}
