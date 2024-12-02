package com.lite.holistic_tracking;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import com.google.mediapipe.components.CameraHelper;
import com.google.mediapipe.components.CameraXPreviewHelper;
import com.google.mediapipe.components.ExternalTextureConverter;
import com.google.mediapipe.components.FrameProcessor;
import com.google.mediapipe.components.PermissionHelper;
import com.google.mediapipe.formats.proto.LandmarkProto;
import com.google.mediapipe.framework.AndroidAssetUtil;

import com.google.mediapipe.glutil.EglManager;
import com.google.mediapipe.formats.proto.LandmarkProto.LandmarkList;


import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;

import java.util.List;

public class holistic_activity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private Interpreter tfliteInterpreter;
    private static final boolean FLIP_FRAMES_VERTICALLY = true;

    private static final int LANDMARK_HISTORY_SIZE = 30;
    private static final int MODEL_INPUT_SIZE = 30*1662;

    static {
        System.loadLibrary("mediapipe_jni");
        try {
            System.loadLibrary("opencv_java3");
        } catch (UnsatisfiedLinkError e) {
            System.loadLibrary("opencv_java4");
        }
    }

    protected FrameProcessor processor;
    protected CameraXPreviewHelper cameraHelper;
    private SurfaceTexture previewFrameTexture;
    private SurfaceView previewDisplayView;
    private EglManager eglManager;
    private ExternalTextureConverter converter;
    private ApplicationInfo applicationInfo;
    private final List<LandmarkList> landmarkHistory = new ArrayList<>();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_holistic_activity);

        try {
            applicationInfo = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Cannot find application info: " + e);
        }

        previewDisplayView = new SurfaceView(this);
        setupPreviewDisplayView();

        AndroidAssetUtil.initializeNativeAssetManager(this);
        eglManager = new EglManager(null);
        processor = new FrameProcessor(
                this,
                eglManager.getNativeContext(),
                applicationInfo.metaData.getString("binaryGraphName"),
                applicationInfo.metaData.getString("inputVideoStreamName"),
                applicationInfo.metaData.getString("outputVideoStreamName")
        );

        processor.getVideoSurfaceOutput().setFlipY(applicationInfo.metaData.getBoolean("flipFramesVertically", FLIP_FRAMES_VERTICALLY));
        PermissionHelper.checkAndRequestCameraPermissions(this);

        try {
            tfliteInterpreter = new Interpreter(loadModelFile());
            Log.e(TAG, "Model başarıyla yüklendi");
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "MODEL YÜKLENEMEDİ: " + e.getMessage());
        }

        // Silinebilir
//        processor.addPacketCallback("output_video", packet -> {
//            LandmarkList landmarks = null;
//            try {
//                landmarks = PacketGetter.getProto(packet, LandmarkList.class);
//            } catch (InvalidProtocolBufferException e) {
//                throw new RuntimeException(e);
//            }
//            synchronized (landmarkHistory) {
//                if (landmarkHistory.size() >= LANDMARK_HISTORY_SIZE) {
//                    landmarkHistory.remove(0);
//                }
//                landmarkHistory.add(landmarks);
//            }
//            processLandmarks(landmarkHistory);
//        });

    }

    @Override
    protected void onResume() {
        super.onResume();
        converter = new ExternalTextureConverter(eglManager.getContext());
        converter.setFlipY(applicationInfo.metaData.getBoolean("flipFramesVertically", FLIP_FRAMES_VERTICALLY));
        converter.setConsumer(processor);


        if (PermissionHelper.cameraPermissionsGranted(this)) {
            startCamera();
        }
    }



    @Override
    protected void onPause() {
        super.onPause();
        converter.close();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    protected void onCameraStarted(SurfaceTexture surfaceTexture) {
        previewFrameTexture = surfaceTexture;
        previewDisplayView.setVisibility(View.VISIBLE);
    }

    protected Size cameraTargetResolution() {
        return null; // No preference and let the camera (helper) decide.
    }

    public void startCamera() {
        cameraHelper = new CameraXPreviewHelper();
        cameraHelper.setOnCameraStartedListener(surfaceTexture -> onCameraStarted(surfaceTexture));
        CameraHelper.CameraFacing cameraFacing = applicationInfo.metaData.getBoolean("cameraFacingFront", false)
                ? CameraHelper.CameraFacing.FRONT
                : CameraHelper.CameraFacing.BACK;
        cameraHelper.startCamera(this, cameraFacing, /*surfaceTexture=*/ null, cameraTargetResolution());
    }

    protected Size computeViewSize(int width, int height) {
        return new Size(width, height);
    }

    protected void onPreviewDisplaySurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Size viewSize = computeViewSize(width, height);
        Size displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize);
        boolean isCameraRotated = cameraHelper.isCameraRotated();
        converter.setSurfaceTextureAndAttachToGLContext(previewFrameTexture,
                isCameraRotated ? displaySize.getHeight() : displaySize.getWidth(),
                isCameraRotated ? displaySize.getWidth() : displaySize.getHeight());
    }

    private void setupPreviewDisplayView() {
        previewDisplayView.setVisibility(View.GONE);
        ViewGroup viewGroup = findViewById(R.id.preview_display_layout);
        viewGroup.addView(previewDisplayView);
        previewDisplayView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                processor.getVideoSurfaceOutput().setSurface(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                onPreviewDisplaySurfaceChanged(holder, format, width, height);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                processor.getVideoSurfaceOutput().setSurface(null);
            }
        });
    }
    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = this.getAssets().openFd("model_mobil_deneme_3.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    // Silinebilir
    private void processLandmarks(List<LandmarkList> landmarkHistory) {
        if (landmarkHistory.size() < LANDMARK_HISTORY_SIZE) {
            Log.e(TAG, "Yeterli landmark verisi toplanmadı. Bekleniyor...");
            return;
        }
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(MODEL_INPUT_SIZE * Float.BYTES);
        inputBuffer.order(ByteOrder.nativeOrder());

        for (LandmarkList landmarks : landmarkHistory) {
            for (LandmarkProto.Landmark landmark : landmarks.getLandmarkList()) {
                inputBuffer.putFloat(landmark.getX());
                inputBuffer.putFloat(landmark.getY());
                inputBuffer.putFloat(landmark.getZ());
                inputBuffer.putFloat(landmark.hasVisibility() ? landmark.getVisibility() : 0.0f);
            }
            float[][] output = new float[1][226]; // 226 sınıf için çıktı boyutu
            tfliteInterpreter.run(inputBuffer, output);
            int predictedClassIndex = -1;
            float maxProbability = -1;
            for (int i = 0; i < output[0].length; i++) {
                if (output[0][i] > maxProbability) {
                    maxProbability = output[0][i];
                    predictedClassIndex = i;
                }
            }
            Log.d(TAG, "Predicted Class: " + predictedClassIndex + " Probability: " + maxProbability);
        }

    }
}