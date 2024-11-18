package org.reactnative.camera.tasks;

import android.app.Activity;
import android.graphics.Rect;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.google.android.cameraview.CameraView;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.Text.Line;
import com.google.mlkit.vision.text.Text.TextBlock;
import com.google.mlkit.vision.text.Text.Element;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.TextRecognizerOptions;

import org.reactnative.camera.utils.ImageDimensions;
import org.reactnative.facedetector.FaceDetectorUtils;
import org.reactnative.frame.RNFrame;
import org.reactnative.frame.RNFrameFactory;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;


public class TextRecognizerAsyncTask {
  private static final String TAG = "TextRecognizerTask";

  private final TextRecognizerAsyncTaskDelegate mDelegate;
  private final WeakReference<ThemedReactContext> mThemedReactContextRef;
  private final WeakReference<Activity> mActivityRef;
  private TextRecognizer mTextRecognizer;
  private final byte[] mImageData;
  private final int mWidth;
  private final int mHeight;
  private final int mRotation;
  private final ImageDimensions mImageDimensions;
  private final double mScaleX;
  private final double mScaleY;
  private final int mPaddingLeft;
  private final int mPaddingTop;
  private final AtomicBoolean isCancelled = new AtomicBoolean(false);

  public TextRecognizerAsyncTask(
          TextRecognizerAsyncTaskDelegate delegate,
          ThemedReactContext themedReactContext,
          byte[] imageData,
          int width,
          int height,
          int rotation,
          float density,
          int facing,
          int viewWidth,
          int viewHeight,
          int viewPaddingLeft,
          int viewPaddingTop
  ) {
    mDelegate = delegate;
    mThemedReactContextRef = new WeakReference<>(themedReactContext);
    mActivityRef = new WeakReference<>(themedReactContext.getCurrentActivity());
    mImageData = imageData;
    mWidth = width;
    mHeight = height;
    mRotation = rotation;
    mImageDimensions = new ImageDimensions(width, height, rotation, facing);
    mScaleX = (double) (viewWidth) / (mImageDimensions.getWidth() * density);
    mScaleY = (double) (viewHeight) / (mImageDimensions.getHeight() * density);
    mPaddingLeft = viewPaddingLeft;
    mPaddingTop = viewPaddingTop;
  }

  public void execute() {
    new Thread(() -> {
      if (isCancelled.get()) return;

      List<TextBlock> textBlocks = null;
      if (mDelegate != null) {
        try {
          mTextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
          RNFrame frame = RNFrameFactory.buildFrame(mImageData, mWidth, mHeight, mRotation);
          InputImage image = frame.getFrame();
          Task<Text> task = mTextRecognizer.process(image);
          textBlocks = task.getResult().getTextBlocks();
        } catch (Exception e) {
          Log.e(TAG, "Error processing image: ", e);
        }
      }

      final List<TextBlock> finalTextBlocks = textBlocks;

      // Check if activity and context are still valid
      ThemedReactContext context = mThemedReactContextRef.get();
      Activity activity = mActivityRef.get();

      if (context != null && activity != null && !activity.isFinishing() && !activity.isDestroyed() && !isCancelled.get()) {
        activity.runOnUiThread(() -> {
          try {
            if (mTextRecognizer != null) {
              mTextRecognizer.close();
            }

            if (finalTextBlocks != null && !isCancelled.get()) {
              WritableArray textBlocksList = Arguments.createArray();
              for (TextBlock textBlock : finalTextBlocks) {
                WritableMap serializedTextBlock = serializeText(textBlock);
                if (mImageDimensions.getFacing() == CameraView.FACING_FRONT) {
                  serializedTextBlock = rotateTextX(serializedTextBlock);
                }
                textBlocksList.pushMap(serializedTextBlock);
              }
              mDelegate.onTextRecognized(textBlocksList);
            }
            mDelegate.onTextRecognizerTaskCompleted();
          } catch (Exception e) {
            Log.e(TAG, "Error in UI thread: ", e);
          }
        });
      } else {
        // Cleanup if context or activity is no longer valid
        if (mTextRecognizer != null) {
          mTextRecognizer.close();
        }
      }
    }).start();
  }

  public void cancel() {
    isCancelled.set(true);
    if (mTextRecognizer != null) {
      mTextRecognizer.close();
    }
  }

  private WritableMap serializeText(TextBlock text) {
    WritableMap encodedText = Arguments.createMap();

    WritableArray components = Arguments.createArray();
    for (Line component : text.getLines()) {
      components.pushMap(serializeText(component));
    }
    encodedText.putArray("components", components);

    encodedText.putString("value", text.getText());
    encodedText.putMap("bounds", serializeBounds(text.getBoundingBox()));
    encodedText.putString("type", "block");

    return encodedText;
  }

  private WritableMap serializeText(Line text) {
    WritableMap encodedText = Arguments.createMap();

    WritableArray components = Arguments.createArray();
    for (Element component : text.getElements()) {
      components.pushMap(serializeText(component));
    }
    encodedText.putArray("components", components);

    encodedText.putString("value", text.getText());
    encodedText.putMap("bounds", serializeBounds(text.getBoundingBox()));
    encodedText.putString("type", "line");

    return encodedText;
  }

  private WritableMap serializeText(Element text) {
    WritableMap encodedText = Arguments.createMap();

    WritableArray components = Arguments.createArray();
    encodedText.putArray("components", components);

    encodedText.putString("value", text.getText());
    encodedText.putMap("bounds", serializeBounds(text.getBoundingBox()));
    encodedText.putString("type", "element");

    return encodedText;
  }

  private WritableMap serializeBounds(Rect boundingBox) {
    int x = boundingBox.left;
    int y = boundingBox.top;
    int width = boundingBox.width();
    int height = boundingBox.height();

    if (x < mWidth / 2) {
      x = x + mPaddingLeft / 2;
    } else if (x > mWidth / 2) {
      x = x - mPaddingLeft / 2;
    }

    if (height < mHeight / 2) {
      y = y + mPaddingTop / 2;
    } else if (height > mHeight / 2) {
      y = y - mPaddingTop / 2;
    }

    WritableMap origin = Arguments.createMap();
    origin.putDouble("x", x * this.mScaleX);
    origin.putDouble("y", y * this.mScaleY);

    WritableMap size = Arguments.createMap();
    size.putDouble("width", width * this.mScaleX);
    size.putDouble("height", height * this.mScaleY);

    WritableMap bounds = Arguments.createMap();
    bounds.putMap("origin", origin);
    bounds.putMap("size", size);

    return bounds;
  }

  private WritableMap rotateTextX(WritableMap text) {
    ReadableMap faceBounds = text.getMap("bounds");

    ReadableMap oldOrigin = faceBounds.getMap("origin");
    WritableMap mirroredOrigin = FaceDetectorUtils.positionMirroredHorizontally(
            oldOrigin, mImageDimensions.getWidth(), mScaleX);

    double translateX = -faceBounds.getMap("size").getDouble("width");
    WritableMap translatedMirroredOrigin = FaceDetectorUtils.positionTranslatedHorizontally(mirroredOrigin, translateX);

    WritableMap newBounds = Arguments.createMap();
    newBounds.merge(faceBounds);
    newBounds.putMap("origin", translatedMirroredOrigin);

    text.putMap("bounds", newBounds);

    ReadableArray oldComponents = text.getArray("components");
    WritableArray newComponents = Arguments.createArray();
    for (int i = 0; i < oldComponents.size(); ++i) {
      WritableMap component = Arguments.createMap();
      component.merge(oldComponents.getMap(i));
      rotateTextX(component);
      newComponents.pushMap(component);
    }
    text.putArray("components", newComponents);

    return text;
  }
}
