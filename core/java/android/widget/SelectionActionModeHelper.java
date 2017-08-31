/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.widget;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.annotation.WorkerThread;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.AsyncTask;
import android.os.LocaleList;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextSelection;
import android.widget.Editor.SelectionModifierCursorController;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Helper class for starting selection action mode
 * (synchronously without the TextClassifier, asynchronously with the TextClassifier).
 * @hide
 */
@UiThread
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
final class SelectionActionModeHelper {

    /**
     * Maximum time (in milliseconds) to wait for a result before timing out.
     */
    // TODO: Consider making this a ViewConfiguration.
    private static final int TIMEOUT_DURATION = 200;

    private static final boolean SMART_SELECT_ANIMATION_ENABLED = true;

    private final Editor mEditor;
    private final TextClassificationHelper mTextClassificationHelper;

    private TextClassification mTextClassification;
    private AsyncTask mTextClassificationAsyncTask;

    private final SelectionTracker mSelectionTracker;
    private final SmartSelectSprite mSmartSelectSprite;

    SelectionActionModeHelper(@NonNull Editor editor) {
        mEditor = Preconditions.checkNotNull(editor);
        final TextView textView = mEditor.getTextView();
        mTextClassificationHelper = new TextClassificationHelper(
                textView.getTextClassifier(), textView.getText(), 0, 1, textView.getTextLocales());
        mSelectionTracker = new SelectionTracker(textView.getTextClassifier());

        if (SMART_SELECT_ANIMATION_ENABLED) {
            mSmartSelectSprite = new SmartSelectSprite(textView);
        } else {
            mSmartSelectSprite = null;
        }
    }

    public void startActionModeAsync(boolean adjustSelection) {
        cancelAsyncTask();
        if (skipTextClassification()) {
            startActionMode(null);
        } else {
            resetTextClassificationHelper(true /* resetSelectionTag */);
            final TextView tv = mEditor.getTextView();
            mTextClassificationAsyncTask = new TextClassificationAsyncTask(
                    tv,
                    TIMEOUT_DURATION,
                    adjustSelection
                            ? mTextClassificationHelper::suggestSelection
                            : mTextClassificationHelper::classifyText,
                    mSmartSelectSprite != null
                            ? this::startActionModeWithSmartSelectAnimation
                            : this::startActionMode)
                    .execute();
        }
    }

    public void invalidateActionModeAsync() {
        cancelAsyncTask();
        if (skipTextClassification()) {
            invalidateActionMode(null);
        } else {
            resetTextClassificationHelper(false /* resetSelectionTag */);
            mTextClassificationAsyncTask = new TextClassificationAsyncTask(
                    mEditor.getTextView(), TIMEOUT_DURATION,
                    mTextClassificationHelper::classifyText, this::invalidateActionMode)
                    .execute();
        }
    }

    public void onSelectionAction() {
        mSelectionTracker.onSelectionAction(mTextClassificationHelper.getSelectionTag());
    }

    public boolean resetSelection(int textIndex) {
        if (mSelectionTracker.resetSelection(
                textIndex, mEditor, mTextClassificationHelper.getSelectionTag())) {
            invalidateActionModeAsync();
            return true;
        }
        return false;
    }

    @Nullable
    public TextClassification getTextClassification() {
        return mTextClassification;
    }

    public void onDestroyActionMode() {
        cancelSmartSelectAnimation();
        mSelectionTracker.onSelectionDestroyed();
        cancelAsyncTask();
    }

    private void cancelAsyncTask() {
        if (mTextClassificationAsyncTask != null) {
            mTextClassificationAsyncTask.cancel(true);
            mTextClassificationAsyncTask = null;
        }
        mTextClassification = null;
    }

    private boolean skipTextClassification() {
        final TextView textView = mEditor.getTextView();
        // No need to make an async call for a no-op TextClassifier.
        final boolean noOpTextClassifier = textView.getTextClassifier() == TextClassifier.NO_OP;
        // Do not call the TextClassifier if there is no selection.
        final boolean noSelection = textView.getSelectionEnd() == textView.getSelectionStart();
        // Do not call the TextClassifier if this is a password field.
        final boolean password = textView.hasPasswordTransformationMethod()
                || TextView.isPasswordInputType(textView.getInputType());
        return noOpTextClassifier || noSelection || password;
    }

    private void startActionMode(@Nullable SelectionResult result) {
        final TextView textView = mEditor.getTextView();
        final CharSequence text = textView.getText();
        mSelectionTracker.setOriginalSelection(
                textView.getSelectionStart(), textView.getSelectionEnd());
        if (result != null && text instanceof Spannable) {
            Selection.setSelection((Spannable) text, result.mStart, result.mEnd);
            mTextClassification = result.mClassification;
        } else {
            mTextClassification = null;
        }
        if (mEditor.startSelectionActionModeInternal()) {
            final SelectionModifierCursorController controller = mEditor.getSelectionController();
            if (controller != null) {
                controller.show();
            }
            if (result != null) {
                mSelectionTracker.onSelectionStarted(
                        result.mStart, result.mEnd, mTextClassificationHelper.getSelectionTag());
            }
        }
        mEditor.setRestartActionModeOnNextRefresh(false);
        mTextClassificationAsyncTask = null;
    }

    private void startActionModeWithSmartSelectAnimation(@Nullable SelectionResult result) {
        final TextView textView = mEditor.getTextView();
        final Layout layout = textView.getLayout();

        final Runnable onAnimationEndCallback = () -> startActionMode(result);
        // TODO do not trigger the animation if the change included only non-printable characters
        final boolean didSelectionChange =
                result != null && (textView.getSelectionStart() != result.mStart
                        || textView.getSelectionEnd() != result.mEnd);

        if (!didSelectionChange) {
            onAnimationEndCallback.run();
            return;
        }

        final List<RectF> selectionRectangles =
                convertSelectionToRectangles(layout, result.mStart, result.mEnd);

        /*
         * Do not run the Smart Select animation when there are multiple lines involved, as this
         * behavior is currently broken.
         *
         * TODO fix Smart Select Animation when the selection spans multiple lines
         */
        if (selectionRectangles.size() != 1) {
            onAnimationEndCallback.run();
            return;
        }

        /*
         * TODO Figure out a more robust approach for this
         * We have to translate all the generated rectangles by the top-left padding of the
         * TextView because the padding influences the rendering of the ViewOverlay, but is not
         * taken into account when generating the selection path rectangles.
         */
        for (RectF rectangle : selectionRectangles) {
            rectangle.left += textView.getPaddingLeft();
            rectangle.right += textView.getPaddingLeft();
            rectangle.top += textView.getPaddingTop();
            rectangle.bottom += textView.getPaddingTop();
        }

        final PointF touchPoint = new PointF(
                mEditor.getLastUpPositionX(),
                mEditor.getLastUpPositionY());

        final PointF animationStartPoint =
                movePointInsideNearestRectangle(touchPoint, selectionRectangles);

        mSmartSelectSprite.startAnimation(
                animationStartPoint,
                selectionRectangles,
                onAnimationEndCallback);
    }

    private List<RectF> convertSelectionToRectangles(final Layout layout, final int start,
            final int end) {
        final List<RectF> result = new ArrayList<>();
        // TODO filter out invalid rectangles
        // getSelection might give us overlapping and zero-dimension rectangles which will interfere
        // with the Smart Select animation
        layout.getSelection(start, end, (left, top, right, bottom) ->
                result.add(new RectF(left, top, right, bottom)));

        result.sort(SmartSelectSprite.RECTANGLE_COMPARATOR);

        return result;
    }

    /** @hide */
    @VisibleForTesting
    public static PointF movePointInsideNearestRectangle(final PointF point,
            final List<RectF> rectangles) {
        float bestX = -1;
        float bestY = -1;
        double bestDistance = Double.MAX_VALUE;

        for (final RectF rectangle : rectangles) {
            final float candidateY = rectangle.centerY();
            final float candidateX;

            if (point.x > rectangle.right) {
                candidateX = rectangle.right;
            } else if (point.x < rectangle.left) {
                candidateX = rectangle.left;
            } else {
                candidateX = point.x;
            }

            final double candidateDistance = Math.pow(point.x - candidateX, 2)
                    + Math.pow(point.y - candidateY, 2);

            if (candidateDistance < bestDistance) {
                bestX = candidateX;
                bestY = candidateY;
                bestDistance = candidateDistance;
            }
        }

        return new PointF(bestX, bestY);
    }

    private void invalidateActionMode(@Nullable SelectionResult result) {
        cancelSmartSelectAnimation();
        mTextClassification = result != null ? result.mClassification : null;
        final ActionMode actionMode = mEditor.getTextActionMode();
        if (actionMode != null) {
            actionMode.invalidate();
        }
        final TextView textView = mEditor.getTextView();
        mSelectionTracker.onSelectionUpdated(
                textView.getSelectionStart(), textView.getSelectionEnd(),
                mTextClassificationHelper.getSelectionTag());
        mTextClassificationAsyncTask = null;
    }

    private void resetTextClassificationHelper(boolean resetSelectionTag) {
        final TextView textView = mEditor.getTextView();
        mTextClassificationHelper.reset(textView.getTextClassifier(), textView.getText(),
                textView.getSelectionStart(), textView.getSelectionEnd(),
                resetSelectionTag, textView.getTextLocales());
    }

    private void cancelSmartSelectAnimation() {
        if (mSmartSelectSprite != null) {
            mSmartSelectSprite.cancelAnimation();
        }
    }

    /**
     * Tracks and logs smart selection changes.
     * It is important to trigger this object's methods at the appropriate event so that it tracks
     * smart selection events appropriately.
     */
    private static final class SelectionTracker {

        // Log event: Smart selection happened.
        private static final String LOG_EVENT_MULTI_SELECTION =
                "textClassifier_multiSelection";
        private static final String LOG_EVENT_SINGLE_SELECTION =
                "textClassifier_singleSelection";

        // Log event: Smart selection acted upon.
        private static final String LOG_EVENT_MULTI_SELECTION_ACTION =
                "textClassifier_multiSelection_action";
        private static final String LOG_EVENT_SINGLE_SELECTION_ACTION =
                "textClassifier_singleSelection_action";

        // Log event: Smart selection was reset to original selection.
        private static final String LOG_EVENT_MULTI_SELECTION_RESET =
                "textClassifier_multiSelection_reset";

        // Log event: Smart selection was user modified.
        private static final String LOG_EVENT_MULTI_SELECTION_MODIFIED =
                "textClassifier_multiSelection_modified";
        private static final String LOG_EVENT_SINGLE_SELECTION_MODIFIED =
                "textClassifier_singleSelection_modified";

        private final TextClassifier mClassifier;

        private int mOriginalStart;
        private int mOriginalEnd;
        private int mSelectionStart;
        private int mSelectionEnd;

        private boolean mMultiSelection;
        private boolean mClassifierSelection;

        SelectionTracker(TextClassifier classifier) {
            mClassifier = classifier;
        }

        /**
         * Called to initialize the original selection before smart selection is triggered.
         */
        public void setOriginalSelection(int selectionStart, int selectionEnd) {
            mOriginalStart = selectionStart;
            mOriginalEnd = selectionEnd;
            resetSelectionFlags();
        }

        /**
         * Called when selection action mode is started and the results come from a classifier.
         * If the selection indices are different from the original selection indices, we have a
         * smart selection.
         */
        public void onSelectionStarted(int selectionStart, int selectionEnd, String logTag) {
            mClassifierSelection = !logTag.isEmpty();
            mSelectionStart = selectionStart;
            mSelectionEnd = selectionEnd;
            // If the started selection is different from the original selection, we have a
            // smart selection.
            mMultiSelection =
                    mSelectionStart != mOriginalStart || mSelectionEnd != mOriginalEnd;
            if (mMultiSelection) {
                mClassifier.logEvent(logTag, LOG_EVENT_MULTI_SELECTION);
            } else if (mClassifierSelection) {
                mClassifier.logEvent(logTag, LOG_EVENT_SINGLE_SELECTION);
            }
        }

        /**
         * Called when selection bounds change.
         */
        public void onSelectionUpdated(int selectionStart, int selectionEnd, String logTag) {
            final boolean selectionChanged =
                    selectionStart != mSelectionStart || selectionEnd != mSelectionEnd;
            if (selectionChanged) {
                if (mMultiSelection) {
                    mClassifier.logEvent(logTag, LOG_EVENT_MULTI_SELECTION_MODIFIED);
                } else if (mClassifierSelection) {
                    mClassifier.logEvent(logTag, LOG_EVENT_SINGLE_SELECTION_MODIFIED);
                }
                resetSelectionFlags();
            }
        }

        /**
         * Called when the selection action mode is destroyed.
         */
        public void onSelectionDestroyed() {
            resetSelectionFlags();
        }

        /**
         * Logs if the action was taken on a smart selection.
         */
        public void onSelectionAction(String logTag) {
            if (mMultiSelection) {
                mClassifier.logEvent(logTag, LOG_EVENT_MULTI_SELECTION_ACTION);
            } else if (mClassifierSelection) {
                mClassifier.logEvent(logTag, LOG_EVENT_SINGLE_SELECTION_ACTION);
            }
        }

        /**
         * Returns true if the current smart selection should be reset to normal selection based on
         * information that has been recorded about the original selection and the smart selection.
         * The expected UX here is to allow the user to select a word inside of the smart selection
         * on a single tap.
         */
        public boolean resetSelection(int textIndex, Editor editor, String logTag) {
            final CharSequence text = editor.getTextView().getText();
            if (mMultiSelection
                    && textIndex >= mSelectionStart && textIndex <= mSelectionEnd
                    && text instanceof Spannable) {
                // Only allow a reset once.
                resetSelectionFlags();
                mClassifier.logEvent(logTag, LOG_EVENT_MULTI_SELECTION_RESET);
                return editor.selectCurrentWord();
            }
            return false;
        }

        private void resetSelectionFlags() {
            mMultiSelection = false;
            mClassifierSelection = false;
        }
    }

    /**
     * AsyncTask for running a query on a background thread and returning the result on the
     * UiThread. The AsyncTask times out after a specified time, returning a null result if the
     * query has not yet returned.
     */
    private static final class TextClassificationAsyncTask
            extends AsyncTask<Void, Void, SelectionResult> {

        private final int mTimeOutDuration;
        private final Supplier<SelectionResult> mSelectionResultSupplier;
        private final Consumer<SelectionResult> mSelectionResultCallback;
        private final TextView mTextView;
        private final String mOriginalText;

        /**
         * @param textView the TextView
         * @param timeOut time in milliseconds to timeout the query if it has not completed
         * @param selectionResultSupplier fetches the selection results. Runs on a background thread
         * @param selectionResultCallback receives the selection results. Runs on the UiThread
         */
        TextClassificationAsyncTask(
                @NonNull TextView textView, int timeOut,
                @NonNull Supplier<SelectionResult> selectionResultSupplier,
                @NonNull Consumer<SelectionResult> selectionResultCallback) {
            super(textView != null ? textView.getHandler() : null);
            mTextView = Preconditions.checkNotNull(textView);
            mTimeOutDuration = timeOut;
            mSelectionResultSupplier = Preconditions.checkNotNull(selectionResultSupplier);
            mSelectionResultCallback = Preconditions.checkNotNull(selectionResultCallback);
            // Make a copy of the original text.
            mOriginalText = mTextView.getText().toString();
        }

        @Override
        @WorkerThread
        protected SelectionResult doInBackground(Void... params) {
            final Runnable onTimeOut = this::onTimeOut;
            mTextView.postDelayed(onTimeOut, mTimeOutDuration);
            final SelectionResult result = mSelectionResultSupplier.get();
            mTextView.removeCallbacks(onTimeOut);
            return result;
        }

        @Override
        @UiThread
        protected void onPostExecute(SelectionResult result) {
            result = TextUtils.equals(mOriginalText, mTextView.getText()) ? result : null;
            mSelectionResultCallback.accept(result);
        }

        private void onTimeOut() {
            if (getStatus() == Status.RUNNING) {
                onPostExecute(null);
            }
            cancel(true);
        }
    }

    /**
     * Helper class for querying the TextClassifier.
     * It trims text so that only text necessary to provide context of the selected text is
     * sent to the TextClassifier.
     */
    private static final class TextClassificationHelper {

        private static final int TRIM_DELTA = 120;  // characters

        private TextClassifier mTextClassifier;

        /** The original TextView text. **/
        private String mText;
        /** Start index relative to mText. */
        private int mSelectionStart;
        /** End index relative to mText. */
        private int mSelectionEnd;
        private LocaleList mLocales;
        /** A tag for the classifier that returned the latest smart selection. */
        private String mSelectionTag = "";

        /** Trimmed text starting from mTrimStart in mText. */
        private CharSequence mTrimmedText;
        /** Index indicating the start of mTrimmedText in mText. */
        private int mTrimStart;
        /** Start index relative to mTrimmedText */
        private int mRelativeStart;
        /** End index relative to mTrimmedText */
        private int mRelativeEnd;

        /** Information about the last classified text to avoid re-running a query. */
        private CharSequence mLastClassificationText;
        private int mLastClassificationSelectionStart;
        private int mLastClassificationSelectionEnd;
        private LocaleList mLastClassificationLocales;
        private SelectionResult mLastClassificationResult;

        TextClassificationHelper(TextClassifier textClassifier,
                CharSequence text, int selectionStart, int selectionEnd, LocaleList locales) {
            reset(textClassifier, text, selectionStart, selectionEnd, true, locales);
        }

        @UiThread
        public void reset(TextClassifier textClassifier,
                CharSequence text, int selectionStart, int selectionEnd,
                boolean resetSelectionTag, LocaleList locales) {
            mTextClassifier = Preconditions.checkNotNull(textClassifier);
            mText = Preconditions.checkNotNull(text).toString();
            mLastClassificationText = null; // invalidate.
            Preconditions.checkArgument(selectionEnd > selectionStart);
            mSelectionStart = selectionStart;
            mSelectionEnd = selectionEnd;
            mLocales = locales;
            if (resetSelectionTag) {
                mSelectionTag = "";
            }
        }

        @WorkerThread
        public SelectionResult classifyText() {
            if (!Objects.equals(mText, mLastClassificationText)
                    || mSelectionStart != mLastClassificationSelectionStart
                    || mSelectionEnd != mLastClassificationSelectionEnd
                    || !Objects.equals(mLocales, mLastClassificationLocales)) {

                mLastClassificationText = mText;
                mLastClassificationSelectionStart = mSelectionStart;
                mLastClassificationSelectionEnd = mSelectionEnd;
                mLastClassificationLocales = mLocales;

                trimText();
                mLastClassificationResult = new SelectionResult(
                        mSelectionStart,
                        mSelectionEnd,
                        mTextClassifier.classifyText(
                                mTrimmedText, mRelativeStart, mRelativeEnd, mLocales));

            }
            return mLastClassificationResult;
        }

        @WorkerThread
        public SelectionResult suggestSelection() {
            trimText();
            final TextSelection sel = mTextClassifier.suggestSelection(
                    mTrimmedText, mRelativeStart, mRelativeEnd, mLocales);
            mSelectionStart = Math.max(0, sel.getSelectionStartIndex() + mTrimStart);
            mSelectionEnd = Math.min(mText.length(), sel.getSelectionEndIndex() + mTrimStart);
            mSelectionTag = sel.getSourceClassifier();
            return classifyText();
        }

        String getSelectionTag() {
            return mSelectionTag;
        }

        private void trimText() {
            mTrimStart = Math.max(0, mSelectionStart - TRIM_DELTA);
            final int referenceEnd = Math.min(mText.length(), mSelectionEnd + TRIM_DELTA);
            mTrimmedText = mText.subSequence(mTrimStart, referenceEnd);
            mRelativeStart = mSelectionStart - mTrimStart;
            mRelativeEnd = mSelectionEnd - mTrimStart;
        }
    }

    /**
     * Selection result.
     */
    private static final class SelectionResult {
        private final int mStart;
        private final int mEnd;
        private final TextClassification mClassification;

        SelectionResult(int start, int end, TextClassification classification) {
            mStart = start;
            mEnd = end;
            mClassification = Preconditions.checkNotNull(classification);
        }
    }
}
