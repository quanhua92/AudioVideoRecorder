package com.quan404.recorduvccamera;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity implements CameraDialog.CameraDialogParent{
    private static final String TAG = "Record UVC Camera";
    private static final boolean DEBUG = true;

    private static final int FRAME_WIDTH = 640;             // 640px
    private static final int FRAME_HEIGHT = 480;            // 480px
    private static final int BITRATE = 6000000;            // 6000000 Mbps
    private static final long DURATION_SEC = 5;             // 8 seconds of video
    /**
     * Shader functions for frame editing
     */
    // Fragment shader that swaps color channels around.
    private static final String SWAPPED_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(sTexture, vTextureCoord).gbra;\n" +
                    "}\n";
    /**
     * Encoder & Muxer
     */
    private CameraEncoder mCameraEncoder = new CameraEncoder();

    /**
     * Camera Stuff
     */
//    private Camera mCamera;
    private SurfaceTextureManager mStManager;

    private SurfaceView surfaceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = (SurfaceView) findViewById(R.id.surfaceView);

        /**
         * UVC Camera functions
         */
        mCameraButton = (ImageButton)findViewById(R.id.camera_button);
        mCameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mUVCCamera == null) {
                    // XXX calling CameraDialog.showDialog is necessary at only first time(only when app has no permission).
                    CameraDialog.showDialog(MainActivity.this);
                } else {
                    synchronized (mSync) {
                        mUVCCamera.destroy();
                        mUVCCamera = null;
                        isActive = isPreview = false;
                    }
                }
            }
        });

        mUVCCameraView = (SurfaceView)findViewById(R.id.camera_surface_view);
        mUVCCameraView.getHolder().addCallback(mSurfaceViewCallback);

        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);


        /**
         * Record uvc camera button
         */

        findViewById(R.id.record_uvc_camera).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Toast.makeText(getApplicationContext(), "Start recording ...", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Start recording ...");

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        prepareCamera(FRAME_WIDTH, FRAME_HEIGHT);
                        mCameraEncoder.prepareEncoder(FRAME_WIDTH, FRAME_HEIGHT, BITRATE);
                        mCameraEncoder.mInputSurface.makeCurrent();
                        prepareSurfaceTexture();

//                        mCamera.startPreview();
                        mUVCCamera.startPreview();

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(), "Start recording", Toast.LENGTH_LONG).show();
                            }
                        });


                        long startWhen = System.nanoTime();
                        long desiredEnd = startWhen + DURATION_SEC * 1000000000L;
                        SurfaceTexture st = mStManager.getSurfaceTexture();
                        int frameCount = 0;

                        while (System.nanoTime() < desiredEnd) {
                            // Feed any pending encoder output into the muxer.
                            mCameraEncoder.drainEncoder(false);

                            // Switch up the colors every 15 frames.  Besides demonstrating the use of
                            // fragment shaders for video editing, this provides a visual indication of
                            // the frame rate: if the camera is capturing at 15fps, the colors will change
                            // once per second.

//                            if ((frameCount % 15) == 0) {
//                                Log.d(TAG, "((frameCount % 15) == 0): change color");
//
//                                String fragmentShader = null;
//                                if ((frameCount & 0x01) != 0) {
//                                    fragmentShader = SWAPPED_FRAGMENT_SHADER;
//                                }
//                                mStManager.changeFragmentShader(fragmentShader);
//                            }

                            frameCount++;

                            // Acquire a new frame of input, and render it to the Surface.  If we had a
                            // GLSurfaceView we could switch EGL contexts and call drawImage() a second
                            // time to render it on screen.  The texture can be shared between contexts by
                            // passing the GLSurfaceView's EGLContext as eglCreateContext()'s share_context
                            // argument.
                            mStManager.awaitNewImage();
                            mStManager.drawImage();

                            // Set the presentation time stamp from the SurfaceTexture's time stamp.  This
                            // will be used by MediaMuxer to set the PTS in the video.
                            if (DEBUG) {
                                Log.d(TAG, "present: " +
                                        ((st.getTimestamp() - startWhen) / 1000000.0) + "ms");
                            }
                            mCameraEncoder.mInputSurface.setPresentationTime(st.getTimestamp());

                            // Submit it to the encoder.  The eglSwapBuffers call will block if the input
                            // is full, which would be bad if it stayed full until we dequeued an output
                            // buffer (which we can't do, since we're stuck here).  So long as we fully drain
                            // the encoder before supplying additional input, the system guarantees that we
                            // can supply another frame without blocking.
                            if (DEBUG) Log.d(TAG, "sending frame to encoder");
                            mCameraEncoder.mInputSurface.swapBuffers();
                        }

                        // send end-of-stream to encoder, and drain remaining output
                        mCameraEncoder.drainEncoder(true);
                    }
                }).start();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mUSBMonitor.register();
    }

    @Override
    protected void onStop() {
        mUSBMonitor.unregister();

        releaseCamera();
        mCameraEncoder.releaseEncoder();
        releaseSurfaceTexture();

        super.onStop();
    }

    /**
     * Camera functions
     */
    private void prepareCamera(int expectedWidth, int expectedHeight) {
        if (DEBUG) {
            Log.d(TAG, "prepareCamera");
        }

//        try {
//            // Open front camera
//            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
//            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
//                Camera.getCameraInfo(i, cameraInfo);
//                if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
//                    mCamera = Camera.open(i);
//                }
//            }
//
//            // Else open rear camera
//            if (mCamera == null) {
//                mCamera = Camera.open();
//            }
//
//            // Else throw an Error
//            if (mCamera == null) {
//                throw new RuntimeException("Can not open any camera");
//            }
//
//            // Select a preview size that match the expectedWidth & expectedHeight
//            Camera.Parameters params = mCamera.getParameters();
//            choosePreviewSize(params, expectedWidth, expectedHeight);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
    }

    private void choosePreviewSize(Camera.Parameters params, int width, int height) {
        Camera.Size ppsfv = params.getPreferredPreviewSizeForVideo();

        if (DEBUG) {
            Log.d(TAG, "choosePreviewSize: PreferredPreviewSize width = " + ppsfv.width + " height = " + ppsfv.height);
        }

        for (Camera.Size size : params.getSupportedPreviewSizes()) {
            if (size.width == width && size.height == height) {
                if (DEBUG)
                    Log.d(TAG, "choosePreviewSize: setPreviewSize to " + width + " " + height);
                params.setPreviewSize(width, height);
                return;
            }
        }

        Log.d(TAG, "choosePreviewSize: Unable to set preview size to " + width + " " + height + " revert to " + ppsfv.width + " " + ppsfv.height);
        if (ppsfv != null) {
            params.setPreviewSize(ppsfv.width, ppsfv.height);
        }
    }

    private void releaseCamera() {
        if (DEBUG) {
            Log.d(TAG, "releaseCamera");
        }
//        if (mCamera != null) {
//            mCamera.stopPreview();
//            mCamera.release();
//            mCamera = null;
//        }
    }



    /**
     * Configures SurfaceTexture for camera preview.  Initializes mStManager, and sets the
     * associated SurfaceTexture as the Camera's "preview texture".
     * <p/>
     * Configure the EGL surface that will be used for output before calling here.
     */
    private void prepareSurfaceTexture() {
        mStManager = new SurfaceTextureManager();
        SurfaceTexture st = mStManager.getSurfaceTexture();
        try {
//            mCamera.setPreviewTexture(st);
//            mUVCCamera.setPreviewTexture(st);
            mUVCCamera.setPreviewDisplay(mPreviewSurface);
            mIFrameCallbackLeft = new MyIFrameCallback(st);
            mUVCCamera.setFrameCallback(mIFrameCallbackLeft, UVCCamera.PIXEL_FORMAT_RGBX);
        } catch (Exception e) {
            throw new RuntimeException("setPreviewTexture failed", e);
        }
    }

    /**
     * SurfaceTexture functions
     */

    /**
     * Releases the SurfaceTexture.
     */
    private void releaseSurfaceTexture() {
        if (mStManager != null) {
            mStManager.release();
            mStManager = null;
        }
    }


    /**
     * UVC CAMERA FUNCTIONS
     */
    // for thread pool
    private static final int CORE_POOL_SIZE = 1;		// initial/minimum threads
    private static final int MAX_POOL_SIZE = 4;			// maximum threads
    private static final int KEEP_ALIVE_TIME = 10;		// time periods while keep the idle thread
    protected static final ThreadPoolExecutor EXECUTER
            = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, KEEP_ALIVE_TIME,
            TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

    private final Object mSync = new Object();
    // for accessing USB and USB camera
    private USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private SurfaceView mUVCCameraView;
    // for open&start / stop&close camera preview
    private ImageButton mCameraButton;
    private Surface mPreviewSurface;
    private boolean isActive, isPreview;

    private final USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onAttach:");
            Toast.makeText(MainActivity.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            if (DEBUG) Log.v(TAG, "onConnect:");
            synchronized (mSync) {
                if (mUVCCamera != null)
                    mUVCCamera.destroy();
                isActive = isPreview = false;
            }
            EXECUTER.execute(new Runnable() {
                @Override
                public void run() {
                    synchronized (mSync) {
                        mUVCCamera = new UVCCamera();
                        mUVCCamera.open(ctrlBlock);
                        if (DEBUG) Log.i(TAG, "supportedSize:" + mUVCCamera.getSupportedSize());
                        try {
                            mUVCCamera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
                        } catch (final IllegalArgumentException e) {
                            try {
                                // fallback to YUV mode
                                mUVCCamera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE);
                            } catch (final IllegalArgumentException e1) {
                                mUVCCamera.destroy();
                                mUVCCamera = null;
                            }
                        }
                        if ((mUVCCamera != null) && (mPreviewSurface != null)) {
                            isActive = true;
//                            mUVCCamera.setPreviewDisplay(mPreviewSurface);
//                            mUVCCamera.startPreview();
                            isPreview = true;
                        }
                    }
                }
            });
        }

        @Override
        public void onDisconnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock) {
            if (DEBUG) Log.v(TAG, "onDisconnect:");
            // XXX you should check whether the comming device equal to camera device that currently using
            synchronized (mSync) {
                if (mUVCCamera != null) {
                    mUVCCamera.close();
                    if (mPreviewSurface != null) {
                        mPreviewSurface.release();
                        mPreviewSurface = null;
                    }
                    isActive = isPreview = false;
                }
            }
        }

        @Override
        public void onDettach(final UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDettach:");
            Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel() {
        }
    };

    /**
     * to access from CameraDialog
     * @return
     */
    @Override
    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }

    private final SurfaceHolder.Callback mSurfaceViewCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(final SurfaceHolder holder) {
            if (DEBUG) Log.v(TAG, "surfaceCreated:");
        }

        @Override
        public void surfaceChanged(final SurfaceHolder holder, final int format, final int width, final int height) {
            if ((width == 0) || (height == 0)) return;
            if (DEBUG) Log.v(TAG, "surfaceChanged:");
            mPreviewSurface = holder.getSurface();
            synchronized (mSync) {
                if (isActive && !isPreview) {
                    mUVCCamera.setPreviewDisplay(mPreviewSurface);
                    mUVCCamera.startPreview();
                    isPreview = true;
                }
            }
        }

        @Override
        public void surfaceDestroyed(final SurfaceHolder holder) {
            if (DEBUG) Log.v(TAG, "surfaceDestroyed:");
            synchronized (mSync) {
                if (mUVCCamera != null) {
                    mUVCCamera.stopPreview();
                }
                isPreview = false;
            }
            mPreviewSurface = null;
        }
    };
    private class MyIFrameCallback implements IFrameCallback{

        private Surface surface;
        private Bitmap bitmap = Bitmap.createBitmap(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
        public MyIFrameCallback(SurfaceTexture surfaceTexture){
            surface = new Surface(surfaceTexture);
        }
        @Override
        public void onFrame(ByteBuffer frame) {
            frame.clear();

            synchronized (bitmap) {
                bitmap.copyPixelsFromBuffer(frame.asReadOnlyBuffer());

                try{
                    Canvas canvas = surface.lockCanvas(null);
                    canvas.drawBitmap(bitmap, 0, 0, null);
                    surface.unlockCanvasAndPost(canvas);


                    Canvas canvasSurface = surfaceView.getHolder().lockCanvas(null);
                    canvasSurface.drawBitmap(bitmap, 0, 0, null);
                    surfaceView.getHolder().unlockCanvasAndPost(canvasSurface);
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
    }
    private IFrameCallback mIFrameCallbackLeft;
}