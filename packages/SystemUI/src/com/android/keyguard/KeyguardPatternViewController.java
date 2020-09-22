/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import static com.android.internal.util.LatencyTracker.ACTION_CHECK_CREDENTIAL;
import static com.android.internal.util.LatencyTracker.ACTION_CHECK_CREDENTIAL_UNLOCKED;

import android.content.res.ColorStateList;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.view.View;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternChecker;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;
import com.android.internal.widget.LockPatternView.Cell;
import com.android.internal.widget.LockscreenCredential;
import com.android.keyguard.EmergencyButton.EmergencyButtonCallback;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.R;

import java.util.List;

public class KeyguardPatternViewController
        extends KeyguardInputViewController<KeyguardPatternView> {

    // how many cells the user has to cross before we poke the wakelock
    private static final int MIN_PATTERN_BEFORE_POKE_WAKELOCK = 2;

    // how long before we clear the wrong pattern
    private static final int PATTERN_CLEAR_TIMEOUT_MS = 2000;

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final LockPatternUtils mLockPatternUtils;
    private final LatencyTracker mLatencyTracker;
    private final KeyguardMessageAreaController.Factory mMessageAreaControllerFactory;

    private KeyguardMessageAreaController mMessageAreaController;
    private LockPatternView mLockPatternView;
    private CountDownTimer mCountdownTimer;
    private KeyguardSecurityCallback mCallback;
    private AsyncTask<?, ?, ?> mPendingLockCheck;

    private EmergencyButtonCallback mEmergencyButtonCallback = new EmergencyButtonCallback() {
        @Override
        public void onEmergencyButtonClickedWhenInCall() {
            mCallback.reset();
        }
    };

    /**
     * Useful for clearing out the wrong pattern after a delay
     */
    private Runnable mCancelPatternRunnable = new Runnable() {
        @Override
        public void run() {
            mLockPatternView.clearPattern();
        }
    };

    private class UnlockPatternListener implements LockPatternView.OnPatternListener {

        @Override
        public void onPatternStart() {
            mLockPatternView.removeCallbacks(mCancelPatternRunnable);
            mMessageAreaController.setMessage("");
        }

        @Override
        public void onPatternCleared() {
        }

        @Override
        public void onPatternCellAdded(List<Cell> pattern) {
            mCallback.userActivity();
            mCallback.onUserInput();
        }

        @Override
        public void onPatternDetected(final List<LockPatternView.Cell> pattern) {
            mKeyguardUpdateMonitor.setCredentialAttempted();
            mLockPatternView.disableInput();
            if (mPendingLockCheck != null) {
                mPendingLockCheck.cancel(false);
            }

            final int userId = KeyguardUpdateMonitor.getCurrentUser();
            if (pattern.size() < LockPatternUtils.MIN_PATTERN_REGISTER_FAIL) {
                mLockPatternView.enableInput();
                onPatternChecked(userId, false, 0, false /* not valid - too short */);
                return;
            }

            mLatencyTracker.onActionStart(ACTION_CHECK_CREDENTIAL);
            mLatencyTracker.onActionStart(ACTION_CHECK_CREDENTIAL_UNLOCKED);
            mPendingLockCheck = LockPatternChecker.checkCredential(
                    mLockPatternUtils,
                    LockscreenCredential.createPattern(pattern),
                    userId,
                    new LockPatternChecker.OnCheckCallback() {

                        @Override
                        public void onEarlyMatched() {
                            mLatencyTracker.onActionEnd(ACTION_CHECK_CREDENTIAL);
                            onPatternChecked(userId, true /* matched */, 0 /* timeoutMs */,
                                    true /* isValidPattern */);
                        }

                        @Override
                        public void onChecked(boolean matched, int timeoutMs) {
                            mLatencyTracker.onActionEnd(ACTION_CHECK_CREDENTIAL_UNLOCKED);
                            mLockPatternView.enableInput();
                            mPendingLockCheck = null;
                            if (!matched) {
                                onPatternChecked(userId, false /* matched */, timeoutMs,
                                        true /* isValidPattern */);
                            }
                        }

                        @Override
                        public void onCancelled() {
                            // We already got dismissed with the early matched callback, so we
                            // cancelled the check. However, we still need to note down the latency.
                            mLatencyTracker.onActionEnd(ACTION_CHECK_CREDENTIAL_UNLOCKED);
                        }
                    });
            if (pattern.size() > MIN_PATTERN_BEFORE_POKE_WAKELOCK) {
                mCallback.userActivity();
                mCallback.onUserInput();
            }
        }

        private void onPatternChecked(int userId, boolean matched, int timeoutMs,
                boolean isValidPattern) {
            boolean dismissKeyguard = KeyguardUpdateMonitor.getCurrentUser() == userId;
            if (matched) {
                mCallback.reportUnlockAttempt(userId, true, 0);
                if (dismissKeyguard) {
                    mLockPatternView.setDisplayMode(LockPatternView.DisplayMode.Correct);
                    mCallback.dismiss(true, userId);
                }
            } else {
                mLockPatternView.setDisplayMode(LockPatternView.DisplayMode.Wrong);
                if (isValidPattern) {
                    mCallback.reportUnlockAttempt(userId, false, timeoutMs);
                    if (timeoutMs > 0) {
                        long deadline = mLockPatternUtils.setLockoutAttemptDeadline(
                                userId, timeoutMs);
                        handleAttemptLockout(deadline);
                    }
                }
                if (timeoutMs == 0) {
                    mMessageAreaController.setMessage(R.string.kg_wrong_pattern);
                    mLockPatternView.postDelayed(mCancelPatternRunnable, PATTERN_CLEAR_TIMEOUT_MS);
                }
            }
        }
    }

    protected KeyguardPatternViewController(KeyguardPatternView view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecurityMode securityMode,
            LockPatternUtils lockPatternUtils,
            KeyguardSecurityCallback keyguardSecurityCallback,
            LatencyTracker latencyTracker,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory) {
        super(view, securityMode, keyguardSecurityCallback);
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mLockPatternUtils = lockPatternUtils;
        mLatencyTracker = latencyTracker;
        mMessageAreaControllerFactory = messageAreaControllerFactory;
        KeyguardMessageArea kma = KeyguardMessageArea.findSecurityMessageDisplay(mView);
        mMessageAreaController = mMessageAreaControllerFactory.create(kma);
        mLockPatternView = mView.findViewById(R.id.lockPatternView);
    }

    @Override
    protected void onViewAttached() {
        mLockPatternView.setOnPatternListener(new UnlockPatternListener());
        mLockPatternView.setSaveEnabled(false);
        mLockPatternView.setInStealthMode(!mLockPatternUtils.isVisiblePatternEnabled(
                KeyguardUpdateMonitor.getCurrentUser()));
        // vibrate mode will be the same for the life of this screen
        mLockPatternView.setTactileFeedbackEnabled(mLockPatternUtils.isTactileFeedbackEnabled());

        EmergencyButton button = mView.findViewById(R.id.emergency_call_button);
        if (button != null) {
            button.setCallback(mEmergencyButtonCallback);
        }

        View cancelBtn = mView.findViewById(R.id.cancel_button);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(view -> {
                mCallback.reset();
                mCallback.onCancelClicked();
            });
        }
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mLockPatternView.setOnPatternListener(null);
        EmergencyButton button = mView.findViewById(R.id.emergency_call_button);
        if (button != null) {
            button.setCallback(null);
        }
        View cancelBtn = mView.findViewById(R.id.cancel_button);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(null);
        }
    }

    @Override
    public void reset() {
        // reset lock pattern
        mLockPatternView.setInStealthMode(!mLockPatternUtils.isVisiblePatternEnabled(
                KeyguardUpdateMonitor.getCurrentUser()));
        mLockPatternView.enableInput();
        mLockPatternView.setEnabled(true);
        mLockPatternView.clearPattern();

        // if the user is currently locked out, enforce it.
        long deadline = mLockPatternUtils.getLockoutAttemptDeadline(
                KeyguardUpdateMonitor.getCurrentUser());
        if (deadline != 0) {
            handleAttemptLockout(deadline);
        } else {
            displayDefaultSecurityMessage();
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        if (mCountdownTimer != null) {
            mCountdownTimer.cancel();
            mCountdownTimer = null;
        }

        if (mPendingLockCheck != null) {
            mPendingLockCheck.cancel(false);
            mPendingLockCheck = null;
        }
        displayDefaultSecurityMessage();
    }

    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    @Override
    public void showPromptReason(int reason) {
        /// TODO: move all this logic into the MessageAreaController?
        switch (reason) {
            case PROMPT_REASON_RESTART:
                mMessageAreaController.setMessage(R.string.kg_prompt_reason_restart_pattern);
                break;
            case PROMPT_REASON_TIMEOUT:
                mMessageAreaController.setMessage(R.string.kg_prompt_reason_timeout_pattern);
                break;
            case PROMPT_REASON_DEVICE_ADMIN:
                mMessageAreaController.setMessage(R.string.kg_prompt_reason_device_admin);
                break;
            case PROMPT_REASON_USER_REQUEST:
                mMessageAreaController.setMessage(R.string.kg_prompt_reason_user_request);
                break;
            case PROMPT_REASON_PREPARE_FOR_UPDATE:
                mMessageAreaController.setMessage(R.string.kg_prompt_reason_timeout_pattern);
                break;
            case PROMPT_REASON_NONE:
                break;
            default:
                mMessageAreaController.setMessage(R.string.kg_prompt_reason_timeout_pattern);
                break;
        }
    }

    @Override
    public void showMessage(CharSequence message, ColorStateList colorState) {
        if (colorState != null) {
            mMessageAreaController.setNextMessageColor(colorState);
        }
        mMessageAreaController.setMessage(message);
    }

    @Override
    public void startAppearAnimation() {
        super.startAppearAnimation();
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return mView.startDisappearAnimation(
                mKeyguardUpdateMonitor.needsSlowUnlockTransition(), finishRunnable);
    }

    private void displayDefaultSecurityMessage() {
        mMessageAreaController.setMessage("");
    }

    private void handleAttemptLockout(long elapsedRealtimeDeadline) {
        mLockPatternView.clearPattern();
        mLockPatternView.setEnabled(false);
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        final long secondsInFuture = (long) Math.ceil(
                (elapsedRealtimeDeadline - elapsedRealtime) / 1000.0);
        mCountdownTimer = new CountDownTimer(secondsInFuture * 1000, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                final int secondsRemaining = (int) Math.round(millisUntilFinished / 1000.0);
                mMessageAreaController.setMessage(mView.getResources().getQuantityString(
                        R.plurals.kg_too_many_failed_attempts_countdown,
                        secondsRemaining, secondsRemaining));
            }

            @Override
            public void onFinish() {
                mLockPatternView.setEnabled(true);
                displayDefaultSecurityMessage();
            }

        }.start();
    }
}
