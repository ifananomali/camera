package io.flutter.plugins.camera;

import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;
import static io.flutter.plugins.camera.CameraUtils.computeBestPreviewSize;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import androidx.annotation.NonNull;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.FlutterView;
import io.flutter.view.TextureRegistry.SurfaceTextureEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

public class Camera {
  private static final String TAG = "CAMERA";
  private final SurfaceTextureEntry flutterTexture;
  private final CameraManager cameraManager;
  private final OrientationEventListener orientationEventListener;
  private final boolean isFrontFacing;
  private final int sensorOrientation;
  private final String cameraName;
  private final Size captureSize;
  private final boolean enableAudio;
  private final boolean slowMoMode;

  private CameraDevice cameraDevice;
  private CameraCaptureSession cameraCaptureSession;
  private CameraConstrainedHighSpeedCaptureSession mPreviewSessionHighSpeed;
  private ImageReader pictureImageReader;
  private ImageReader imageStreamReader;
  private EventChannel.EventSink eventSink;
  private CaptureRequest.Builder captureRequestBuilder;
  private MediaRecorder mediaRecorder;
  private boolean recordingVideo;
  private boolean supportMonoEffect = false;
  private boolean isFocusLocked = false;
  private CamcorderProfile recordingProfile;
  private int currentOrientation = ORIENTATION_UNKNOWN;
  private Size mPreviewSize;
  private Size mVideoSize;
  private Range<Integer>[] availableFpsRange;

  public boolean flashMode;

  // Mirrors camera.dart
  public enum ResolutionPreset {
    low,
    medium,
    high,
    veryHigh,
    ultraHigh,
    max,
  }

  public Camera(
      final Activity activity,
      final FlutterView flutterView,
      final String cameraName,
      final String resolutionPreset,
      final boolean enableAudio,
      final boolean slowMoMode)
      throws CameraAccessException {
    if (activity == null) {
      throw new IllegalStateException("No activity available!");
    }

    this.cameraName = cameraName;
    this.enableAudio = enableAudio;
    this.slowMoMode = slowMoMode;
    this.flutterTexture = flutterView.createSurfaceTexture();
    this.cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    orientationEventListener =
        new OrientationEventListener(activity.getApplicationContext()) {
          @Override
          public void onOrientationChanged(int i) {
            if (i == ORIENTATION_UNKNOWN) {
              return;
            }
            // Convert the raw deg angle to the nearest multiple of 90.
            currentOrientation = (int) Math.round(i / 90.0) * 90;
          }
        };
    orientationEventListener.enable();

    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);
    int[] effects = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS);

    for (int effect : effects) {
      if (effect == 1) { // MONO
        supportMonoEffect = true;
      }
    }

    StreamConfigurationMap streamConfigurationMap =
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    //noinspection ConstantConditions
    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    //noinspection ConstantConditions
    isFrontFacing =
        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT;
    ResolutionPreset preset = ResolutionPreset.valueOf(resolutionPreset);
    recordingProfile =
        CameraUtils.getBestAvailableCamcorderProfileForResolutionPreset(cameraName, preset);
    captureSize = new Size(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight);

    StreamConfigurationMap map = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    assert map != null;
    if (slowMoMode) {
      mVideoSize = chooseVideoSize(map.getHighSpeedVideoSizes());
      for (Size size : map.getHighSpeedVideoSizes()) {
        Log.d("RESOLUTION", size.toString());
      }
      mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
              1920, 1080, mVideoSize);
      // FPS
      availableFpsRange = map.getHighSpeedVideoFpsRangesFor(mVideoSize);
      int max = 0;
      int min;
      for (Range<Integer> r : availableFpsRange) {
        if (max < r.getUpper()) {
          max = r.getUpper();
        }
      }
      min = max;
      for (Range<Integer> r : availableFpsRange) {
        if (min > r.getLower()) {
          min = r.getUpper();
        }
      }
      for (Range<Integer> r : availableFpsRange) {
        Log.d("RANGES", "[ " + r.getLower() + " , " + r.getUpper() + " ]");
      }
      Log.d("RANGE", "[ " + min + " , " + max + " ]");
    } else {
      try {
        mVideoSize = chooseVideoSize(map.getHighSpeedVideoSizes());
        for (Size size : map.getHighSpeedVideoSizes()) {
          Log.d("RESOLUTION", size.toString());
        }
        mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                2560, 1440, new Size(2560, 1440));
      } catch (Exception e) {
        mPreviewSize = computeBestPreviewSize(cameraName, preset);
      }
    }
  }

  private static class CompareSizesByArea implements Comparator<Size> {
    @Override
    public int compare(Size lhs, Size rhs) {
      // We cast here to ensure the multiplications won't overflow.
      return Long.signum(
              (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
    }
  }

  private Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
    // Collect the supported resolutions that are at least as big as the preview Surface
    List<Size> bigEnough = new ArrayList<>();
    int w = aspectRatio.getWidth();
    int h = aspectRatio.getHeight();
    for (Size option : choices) {
      if (option.getHeight() == option.getWidth() * h / w &&
              option.getWidth() <= width && option.getHeight() <= height) {
        bigEnough.add(option);
      }
    }
    // Pick the smallest of those, assuming we found any
    if (bigEnough.size() > 0) {
      return Collections.max(bigEnough, new CompareSizesByArea());
    } else {
      Log.e(TAG, "Couldn't find any suitable preview size");
      return choices[0];
    }
  }

  private Size chooseVideoSize(Size[] choices) {
    for (Size size : choices) {
      if (size.getWidth() == 1920 && size.getHeight() <= 1080) {
        return size;
      }
    }
    Log.e(TAG, "Couldn't find any suitable video size");
    return choices[choices.length - 1];
  }

  public void setupCameraEventChannel(EventChannel cameraEventChannel) {
    cameraEventChannel.setStreamHandler(
        new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object arguments, EventChannel.EventSink sink) {
            eventSink = sink;
          }

          @Override
          public void onCancel(Object arguments) {
            eventSink = null;
          }
        });
  }

  private void prepareMediaRecorder(String outputFilePath) throws IOException {
    if (mediaRecorder != null) {
      mediaRecorder.release();
    }
    mediaRecorder = new MediaRecorder();

    // There's a specific order that mediaRecorder expects. Do not change the order
    // of these function calls.
    if (enableAudio) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
    mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
    mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
    if (enableAudio) mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
    mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
    mediaRecorder.setVideoEncodingBitRate(2000000000);
    if (enableAudio) mediaRecorder.setAudioSamplingRate(16000);
    mediaRecorder.setVideoFrameRate(240);
    mediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
    mediaRecorder.setOutputFile(outputFilePath);
    mediaRecorder.setOrientationHint(getMediaOrientation());

    mediaRecorder.prepare();
  }

  @SuppressLint("MissingPermission")
  public void open(@NonNull final Result result) throws CameraAccessException {
    pictureImageReader =
        ImageReader.newInstance(
            captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);

    // Used to steam image byte data to dart side.
    imageStreamReader =
        ImageReader.newInstance(
            mPreviewSize.getWidth(), mPreviewSize.getHeight(), ImageFormat.YUV_420_888, 2);

    cameraManager.openCamera(
        cameraName,
        new CameraDevice.StateCallback() {
          @Override
          public void onOpened(@NonNull CameraDevice device) {
            cameraDevice = device;
            try {
              startPreview();
            } catch (CameraAccessException e) {
              result.error("CameraAccess", e.getMessage(), null);
              close();
              return;
            }
            Map<String, Object> reply = new HashMap<>();
            reply.put("textureId", flutterTexture.id());
            reply.put("previewWidth", mPreviewSize.getWidth());
            reply.put("previewHeight", mPreviewSize.getHeight());
            result.success(reply);
          }

          @Override
          public void onClosed(@NonNull CameraDevice camera) {
            sendEvent(EventType.CAMERA_CLOSING);
            super.onClosed(camera);
          }

          @Override
          public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            close();
            sendEvent(EventType.ERROR, "The camera was disconnected.");
          }

          @Override
          public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
            close();
            String errorDescription;
            switch (errorCode) {
              case ERROR_CAMERA_IN_USE:
                errorDescription = "The camera device is in use already.";
                break;
              case ERROR_MAX_CAMERAS_IN_USE:
                errorDescription = "Max cameras in use";
                break;
              case ERROR_CAMERA_DISABLED:
                errorDescription = "The camera device could not be opened due to a device policy.";
                break;
              case ERROR_CAMERA_DEVICE:
                errorDescription = "The camera device has encountered a fatal error";
                break;
              case ERROR_CAMERA_SERVICE:
                errorDescription = "The camera service has encountered a fatal error.";
                break;
              default:
                errorDescription = "Unknown camera error";
            }
            sendEvent(EventType.ERROR, errorDescription);
          }
        },
        null);
  }

  private void writeToFile(ByteBuffer buffer, File file) throws IOException {
    try (FileOutputStream outputStream = new FileOutputStream(file)) {
      while (0 < buffer.remaining()) {
        outputStream.getChannel().write(buffer);
      }
    }
  }

  SurfaceTextureEntry getFlutterTexture() {
    return flutterTexture;
  }

  public void takePicture(String filePath, @NonNull final Result result) {
    final File file = new File(filePath);

    if (file.exists()) {
      result.error(
          "fileExists", "File at path '" + filePath + "' already exists. Cannot overwrite.", null);
      return;
    }

    pictureImageReader.setOnImageAvailableListener(
        reader -> {
          try (Image image = reader.acquireLatestImage()) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            writeToFile(buffer, file);
            result.success(null);
          } catch (IOException e) {
            result.error("IOError", "Failed saving image", null);
          }
        },
        null);

    try {
      final CaptureRequest.Builder captureBuilder =
          cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
      captureBuilder.addTarget(pictureImageReader.getSurface());
      captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());
      captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);

      if (supportMonoEffect) {
        captureBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_MONO);
      }

      if (flashMode) {
        captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
      } else {
        captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
      }

      cameraCaptureSession.capture(
          captureBuilder.build(),
          new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureFailed(
                @NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request,
                @NonNull CaptureFailure failure) {
              String reason;
              switch (failure.getReason()) {
                case CaptureFailure.REASON_ERROR:
                  reason = "An error happened in the framework";
                  break;
                case CaptureFailure.REASON_FLUSHED:
                  reason = "The capture has failed due to an abortCaptures() call";
                  break;
                default:
                  reason = "Unknown reason";
              }
              result.error("captureFailure", reason, null);
            }
          },
          null);
    } catch (CameraAccessException e) {
      result.error("cameraAccess", e.getMessage(), null);
    }
  }

  private void createCaptureSession(int templateType, Surface... surfaces)
      throws CameraAccessException {
    createCaptureSession(templateType, null, surfaces);
  }

  private void createCaptureSession(
      int templateType, Runnable onSuccessCallback, Surface... surfaces)
      throws CameraAccessException {
    // Close any existing capture session.
    closeCaptureSession();

    // Create a new capture builder.
    captureRequestBuilder = cameraDevice.createCaptureRequest(templateType);

    // Build Flutter surface to render to
    SurfaceTexture surfaceTexture = flutterTexture.surfaceTexture();
    surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
    Surface flutterSurface = new Surface(surfaceTexture);
    captureRequestBuilder.addTarget(flutterSurface);

    List<Surface> remainingSurfaces = Arrays.asList(surfaces);
    if (templateType != CameraDevice.TEMPLATE_PREVIEW) {
      // If it is not preview mode, add all surfaces as targets.
      for (Surface surface : remainingSurfaces) {
        captureRequestBuilder.addTarget(surface);
      }
    }

    // Prepare the callback
    CameraCaptureSession.StateCallback callback =
        new CameraCaptureSession.StateCallback() {
          @Override
          public void onConfigured(@NonNull CameraCaptureSession session) {
            try {
              if (cameraDevice == null) {
                sendEvent(EventType.ERROR, "The camera was closed during configuration.");
                return;
              }
              cameraCaptureSession = session;
              captureRequestBuilder.set(
                  CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
              captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_EDOF);

              cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
              if (onSuccessCallback != null) {
                onSuccessCallback.run();
              }
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
              sendEvent(EventType.ERROR, e.getMessage());
            }
          }

          @Override
          public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            sendEvent(EventType.ERROR, "Failed to configure camera session.");
          }
        };

    // Collect all surfaces we want to render to.
    List<Surface> surfaceList = new ArrayList<>();
    surfaceList.add(flutterSurface);
    surfaceList.addAll(remainingSurfaces);
    // Start the session
    cameraDevice.createCaptureSession(surfaceList, callback, null);
  }

  private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
    Range<Integer> fpsRange = Range.create(240, 240);
    //Range<Integer> fpsRange = getHighestFpsRange(availableFpsRange);
    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);

    if (supportMonoEffect) {
      builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_MONO);
    }
  }

  private void updatePreview() {
    if (cameraDevice == null) {
      return;
    }
    try {
      if (slowMoMode && recordingVideo) {
        setUpCaptureRequestBuilder(captureRequestBuilder);
        List<CaptureRequest> mPreviewBuilderBurst = null;
        if (slowMoMode && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
          mPreviewBuilderBurst = mPreviewSessionHighSpeed.createHighSpeedRequestList(captureRequestBuilder.build());
        }
        mPreviewSessionHighSpeed.setRepeatingBurst(mPreviewBuilderBurst, null, null);
      } else {
        //captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
          cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
        } catch (Exception e) {
        }
      }
    } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
      sendEvent(EventType.ERROR, e.getMessage());
    }
  }

  public void startVideoRecording(String filePath, Result result) {
    if (new File(filePath).exists()) {
      result.error("fileExists", "File at path '" + filePath + "' already exists.", null);
      return;
    }
    try {
      closeCaptureSession();
      prepareMediaRecorder(filePath);

      recordingVideo = true;

      SurfaceTexture surfaceTexture = flutterTexture.surfaceTexture();
      surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
      captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

      if (flashMode) {
        captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
      } else {
        captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
      }

      if (supportMonoEffect) {
        captureRequestBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_MONO);
      }

      List<Surface> surfaces = new ArrayList<>();

      Surface previewSurface = new Surface(surfaceTexture);
      surfaces.add(previewSurface);
      captureRequestBuilder.addTarget(previewSurface);

      Surface recorderSurface = mediaRecorder.getSurface();
      surfaces.add(recorderSurface);
      captureRequestBuilder.addTarget(recorderSurface);

      if (slowMoMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        cameraDevice.createConstrainedHighSpeedCaptureSession (
                surfaces,
                new CameraCaptureSession.StateCallback() {
                  @Override
                  public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) {
                      result.error("configureFailed", "Camera was closed during configuration", null);
                      return;
                    }

                    cameraCaptureSession = session;
                    mPreviewSessionHighSpeed = (CameraConstrainedHighSpeedCaptureSession) cameraCaptureSession;
                    if (isFocusLocked) {
                      lockFocus(new PointF(0f, 0f));
                    }
                    updatePreview();

                    mediaRecorder.start();
                    result.success(null);
                  }

                  @Override
                  public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    result.error("configureFailed", "Failed to configure camera session", null);
                  }
                },
                null);
      } else {
        cameraDevice.createCaptureSession(
                surfaces,
                new CameraCaptureSession.StateCallback() {
                  @Override
                  public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) {
                      result.error("configureFailed", "Camera was closed during configuration", null);
                      return;
                    }

                    cameraCaptureSession = session;
                    if (isFocusLocked) {
                      lockFocus(new PointF(0f, 0f));
                    }
                    updatePreview();

                    mediaRecorder.start();
                    result.success(null);
                  }

                  @Override
                  public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    result.error("configureFailed", "Failed to configure camera session", null);
                  }
                },
                null);
      }
    } catch (CameraAccessException | IOException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void stopVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      recordingVideo = false;
      mediaRecorder.stop();
      mediaRecorder.reset();
      if (slowMoMode) {
        mPreviewSessionHighSpeed.stopRepeating();
      } else {
        cameraCaptureSession.stopRepeating();
      }
      startPreview();
      result.success(null);
    } catch (CameraAccessException | IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void pauseVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.pause();
      } else {
        result.error("videoRecordingFailed", "pauseVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void resumeVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.resume();
      } else {
        result.error(
            "videoRecordingFailed", "resumeVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void startPreview() throws CameraAccessException {
    //createCaptureSession(CameraDevice.TEMPLATE_PREVIEW, pictureImageReader.getSurface());

    closeCaptureSession();

    SurfaceTexture surfaceTexture = flutterTexture.surfaceTexture();
    surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
    captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

    if (supportMonoEffect) {
      captureRequestBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_MONO);
    }

    List<Surface> surfaces = new ArrayList<>();

    Surface previewSurface = new Surface(surfaceTexture);
    surfaces.add(previewSurface);
    captureRequestBuilder.addTarget(previewSurface);

    surfaces.add(pictureImageReader.getSurface());

    cameraDevice.createCaptureSession(
            surfaces,
            new CameraCaptureSession.StateCallback() {

              @Override
              public void onConfigured(@NonNull CameraCaptureSession session) {
                if (cameraDevice == null) {
                  sendEvent(EventType.ERROR,"The camera was closed during configuration.");
                  return;
                }
                cameraCaptureSession = session;
                updatePreview();
              }

              @Override
              public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                sendEvent(EventType.ERROR,"Failed to configure the camera for preview.");
              }
            },
            null);
  }

  public void startPreviewWithImageStream(EventChannel imageStreamChannel)
      throws CameraAccessException {
    //createCaptureSession(CameraDevice.TEMPLATE_STILL_CAPTURE, imageStreamReader.getSurface());
    closeCaptureSession();

    SurfaceTexture surfaceTexture = flutterTexture.surfaceTexture();
    surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

    captureRequestBuilder =
            cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

    List<Surface> surfaces = new ArrayList<>();

    Surface previewSurface = new Surface(surfaceTexture);
    surfaces.add(previewSurface);
    captureRequestBuilder.addTarget(previewSurface);

    surfaces.add(imageStreamReader.getSurface());
    captureRequestBuilder.addTarget(imageStreamReader.getSurface());

    cameraDevice.createCaptureSession(
            surfaces,
            new CameraCaptureSession.StateCallback() {
              @Override
              public void onConfigured(@NonNull CameraCaptureSession session) {
                if (cameraDevice == null) {
                  sendEvent(EventType.ERROR, "The camera was closed during configuration.");
                  return;
                }
                try {
                  cameraCaptureSession = session;
                  captureRequestBuilder.set(
                          CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                  cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                  sendEvent(EventType.ERROR, e.getMessage());
                }
              }

              @Override
              public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                sendEvent(EventType.ERROR, "Failed to configure the camera for streaming images.");
              }
            },
            null);

    imageStreamChannel.setStreamHandler(
        new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object o, EventChannel.EventSink imageStreamSink) {
            setImageStreamImageAvailableListener(imageStreamSink);
          }

          @Override
          public void onCancel(Object o) {
            imageStreamReader.setOnImageAvailableListener(null, null);
          }
        });
  }

  private void setImageStreamImageAvailableListener(final EventChannel.EventSink imageStreamSink) {
    imageStreamReader.setOnImageAvailableListener(
        reader -> {
          Image img = reader.acquireLatestImage();
          if (img == null) return;

          List<Map<String, Object>> planes = new ArrayList<>();
          for (Image.Plane plane : img.getPlanes()) {
            ByteBuffer buffer = plane.getBuffer();

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes, 0, bytes.length);

            Map<String, Object> planeBuffer = new HashMap<>();
            planeBuffer.put("bytesPerRow", plane.getRowStride());
            planeBuffer.put("bytesPerPixel", plane.getPixelStride());
            planeBuffer.put("bytes", bytes);

            planes.add(planeBuffer);
          }

          Map<String, Object> imageBuffer = new HashMap<>();
          imageBuffer.put("width", img.getWidth());
          imageBuffer.put("height", img.getHeight());
          imageBuffer.put("format", img.getFormat());
          imageBuffer.put("planes", planes);

          imageStreamSink.success(imageBuffer);
          img.close();
        },
        null);
  }

  private int clamp(int x, int min, int max) {
    if (x < min) {
      return min;
    } else if (x > max) {
      return max;
    } else {
      return x;
    }
  }

  public void lockFocus(PointF point) {
    isFocusLocked = true;
    CameraCharacteristics characteristics = null;
    try {
      characteristics = cameraManager.getCameraCharacteristics(cameraName);
    } catch (CameraAccessException e) {
      e.printStackTrace();
    }
    Rect rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
    int areaSize = 250;
    int right = rect.right;
    int bottom = rect.bottom;
    int viewWidth = mPreviewSize.getWidth();
    int viewHeight = mPreviewSize.getHeight();
    int ll, rr;
    Rect newRect;
    int centerX = (int) 0;
    int centerY = (int) 0;
    ll = ((centerX * right) - areaSize) / viewWidth;
    rr = ((centerY * bottom) - areaSize) / viewHeight;
    int focusLeft = clamp(ll, 0, right);
    int focusBottom = clamp(rr, 0, bottom);
    newRect = new Rect(focusLeft, focusBottom, focusLeft + areaSize, focusBottom + areaSize);
    MeteringRectangle meteringRectangle = new MeteringRectangle(newRect, 500);
    MeteringRectangle[] meteringRectangleArr = {meteringRectangle};
    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, meteringRectangleArr);
    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
    updatePreview();
    Log.d(TAG, String.valueOf(point));
  }

  public void unlockFocus() {
    isFocusLocked = false;
    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
    captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    updatePreview();
  }

  private void sendEvent(EventType eventType) {
    sendEvent(eventType, null);
  }

  private void sendEvent(EventType eventType, String description) {
    if (eventSink != null) {
      Map<String, String> event = new HashMap<>();
      event.put("eventType", eventType.toString().toLowerCase());
      // Only errors have description
      if (eventType != EventType.ERROR) {
        event.put("errorDescription", description);
      }
      eventSink.success(event);
    }
  }

  private void closeCaptureSession() {
    if (cameraCaptureSession != null) {
      cameraCaptureSession.close();
      cameraCaptureSession = null;
    }
  }

  public void close() {
    closeCaptureSession();

    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;
    }
    if (pictureImageReader != null) {
      pictureImageReader.close();
      pictureImageReader = null;
    }
    if (imageStreamReader != null) {
      imageStreamReader.close();
      imageStreamReader = null;
    }
    if (mediaRecorder != null) {
      mediaRecorder.reset();
      mediaRecorder.release();
      mediaRecorder = null;
    }
  }

  public void dispose() {
    close();
    flutterTexture.release();
    orientationEventListener.disable();
  }

  private int getMediaOrientation() {
    final int sensorOrientationOffset =
        (currentOrientation == ORIENTATION_UNKNOWN)
            ? 0
            : (isFrontFacing) ? -currentOrientation : currentOrientation;
    return (sensorOrientationOffset + sensorOrientation + 360) % 360;
  }

  private enum EventType {
    ERROR,
    CAMERA_CLOSING,
  }
}
