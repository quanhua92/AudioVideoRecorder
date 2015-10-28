package com.quan404.recorduvccamera;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.usb.UsbDevice;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.Environment;
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

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity implements CameraDialog.CameraDialogParent{
    private static final String TAG = "Record UVC Camera";
    private static final boolean DEBUG = true;

    private SurfaceView surfaceView;
    private Thread myThread;

    private Object mFrameSyncObject = new Object();     // guards mFrameAvailable
    private boolean mFrameAvailable = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceView.getHolder().setFixedSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT);

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

                if ((mUVCCamera != null) && (mPreviewSurface != null)) {
                    isActive = true;
                    mUVCCamera.setPreviewDisplay(mPreviewSurface);
                    mIFrameCallbackLeft = new MyIFrameCallback();
                    mUVCCamera.setFrameCallback(mIFrameCallbackLeft, UVCCamera.PIXEL_FORMAT_RGBX);
                    mUVCCamera.startPreview();
                    isPreview = true;
                }

                myThread = new Thread(new EncodingThread(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, 6000000));
                myThread.start();
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

        if(myThread != null){
            try{
                myThread.interrupt();
            }catch (Exception e){
                e.printStackTrace();
            }

        }

        super.onStop();
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

    private byte[] nv21Data = null;
    private class MyIFrameCallback implements IFrameCallback{
        private Bitmap bitmap = Bitmap.createBitmap(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
        private boolean stillProcessing = true;
        @Override
        public void onFrame(ByteBuffer frame) {
            frame.clear();

            synchronized (bitmap) {
                bitmap.copyPixelsFromBuffer(frame.asReadOnlyBuffer());
            }

            try{
                Canvas canvasSurface = surfaceView.getHolder().lockCanvas(null);
                canvasSurface.drawBitmap(bitmap, 0, 0, null);
                surfaceView.getHolder().unlockCanvasAndPost(canvasSurface);
            }catch (Exception e){
                e.printStackTrace();
            }

            if (stillProcessing){
                nv21Data = getNV21(bitmap.getWidth(), bitmap.getHeight(), bitmap, false);

                synchronized (mFrameSyncObject){
                    if(mFrameAvailable){
                        Log.e(TAG, "mFrameAvailable already set");
                        stillProcessing = false;
                    }
                    mFrameAvailable = true;
                    mFrameSyncObject.notifyAll();
                }
            }

        }
    }
    private IFrameCallback mIFrameCallbackLeft;


    /**
     * Encoding Thread
     */
    public class EncodingThread implements Runnable {
        private String TAG = "EncodingThread";
        private boolean DEBUG = true;

        /**
         * MediaCodec Stuffs
         */
        private MediaCodec mEncoder = null;

        // parameters for the encoder
        private String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
        private int IFRAME_INTERVAL = 10;          // 10 seconds between I-frames
        private int FRAME_RATE = 15;
        private int DURATION_SEC = 15;             // 8 seconds of video

        private int mWidth;
        private int mHeight;
        private int mBitRate;
        private int colorFormat;
        /**
         * MediaMuxer
         */
        private MediaMuxer mMuxer;

        private File OUTPUT_DIR = Environment.getExternalStorageDirectory();

        public EncodingThread(int width, int height, int bitrate){
            if (DEBUG) Log.d(TAG, "Constructor");

            mWidth = width;
            mHeight = height;
            mBitRate = bitrate;
        }
        @Override
        public void run() {
            Log.d(TAG, "================================================");
            Log.d(TAG, "start encoding");

            /**
             * Prepare Encoder
             */
            prepareEncoder();

            /**
             * Prepare MediaMuxer
             */
            prepareMuxer();

            /**
             * Generate video and save to sdcard
             */
            doGenerateSaveVideo();

            /**
             * Release Encoder
             */
            releaseEncoder();

            /**
             * Release Muxer
             */
            releaseMuxer();

            /**
             * Finish Thread
             */
            Log.d(TAG, "finish encoding");
            Log.d(TAG, "================================================");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "Finish: saved video to sdcard", Toast.LENGTH_SHORT).show();
                }
            });
        }

        private void prepareMuxer(){

            String outputPath = new File(OUTPUT_DIR, "test.mp4").toString();
            if(DEBUG) Log.i(TAG, "Output file is " + outputPath);

            // Create a MediaMuxer. We can't add the video track and start() the muxer here,
            // because our MediaFormat doesn't have the Magic Goodies.  These can only be
            // obtained from the encoder after it has started processing data.
            //
            // We're not actually interested in multiplexing audio.  We just want to convert
            // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
            try {
                mMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (IOException ioe) {
                throw new RuntimeException("MediaMuxer creation failed", ioe);
            }
        }

        private void releaseMuxer(){
            if (mMuxer != null) {
                try {
                    mMuxer.stop();
                    mMuxer.release();
                    mMuxer = null;
                } catch (Exception e) {
                    Log.e(TAG, "You started a Muxer but haven't fed any data into it");
                    e.printStackTrace();
                }
            }
        }

        private NV21Convertor convertor;

        private void prepareEncoder(){
            try {

                MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
                if (codecInfo == null) {
                    // Don't fail CTS if they don't have an AVC codec (not here, anyway).
                    if (DEBUG) Log.e(TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
                    return;
                }
                if (DEBUG) Log.d(TAG, "found codec: " + codecInfo.getName());


                colorFormat = selectColorFormat(codecInfo, MIME_TYPE);

                if (DEBUG) Log.d(TAG, "found colorFormat: " + colorFormat);
                // We avoid the device-specific limitations on width and height by using values that
                // are multiples of 16, which all tested devices seem to be able to handle.
                MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
                // Set some properties.  Failing to specify some of these can cause the MediaCodec
                // configure() call to throw an unhelpful exception.
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
                format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
                if (DEBUG) Log.d(TAG, "format: " + format);
                // Create a MediaCodec for the desired codec, then configure it as an encoder with
                // our desired properties.
                mEncoder = MediaCodec.createByCodecName(codecInfo.getName());
                mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                convertor = new NV21Convertor();
                convertor.setSize(mWidth, mHeight);
                convertor.setSliceHeigth(mHeight);
                convertor.setStride(mWidth);
                convertor.setYPadding(0);
                convertor.setEncoderColorFormat(colorFormat);

                mEncoder.start();
            } catch (Exception e){
                e.printStackTrace();
            }
        }

        private void releaseEncoder(){
            if (DEBUG) Log.d(TAG, "releasing codec");

            if (mEncoder != null) {
                mEncoder.stop();
                mEncoder.release();
            }
        }

        private void doGenerateSaveVideo(){
            if (DEBUG) Log.d(TAG, "---------- doGenerateSaveVideo ------------");
            /**
             * Init parameters
             */
            final int TIMEOUT_USEC = 10000;
            ByteBuffer[] encoderInputBuffers = mEncoder.getInputBuffers();
            ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int generateIndex = 0;
            int NUM_FRAMES = DURATION_SEC * FRAME_RATE; // number of frame required to generate

            // The size of a frame of video data, in the formats we handle, is stride*sliceHeight
            // for Y, and (stride/2)*(sliceHeight/2) for each of the Cb and Cr channels.  Application
            // of algebra and assuming that stride==width and sliceHeight==height yields:
            byte[] frameData = new byte[mWidth * mHeight * 3 / 2];

            /**
             * Populate imageData byte[] with an image
             */

            /**
             * Loop through 5 seconds and generate + save file
             */

            boolean inputDone = false;
            boolean encoderDone = false;
            while (!encoderDone) {
                if(DEBUG) Log.d(TAG, "---- Loop ----");
                /**
                 * Generate
                 */
                if(!inputDone){
                    int inputBufIndex = 0;

                    inputBufIndex = mEncoder.dequeueInputBuffer(TIMEOUT_USEC);
                    if (DEBUG) Log.d(TAG, "inputBufIndex=" + inputBufIndex);

                    if ( inputBufIndex >= 0 ) {
                        long ptsUsec = computePresentationTime(generateIndex);
                        if ( generateIndex == NUM_FRAMES ) {
                            inputDone = true;
                            // Send an empty frame with the end-of-stream flag set.  If we set EOS
                            // on a frame with data, that frame data will be ignored, and the
                            // output will be short one frame.

                            mEncoder.queueInputBuffer(inputBufIndex, 0, 0, ptsUsec,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                            if (DEBUG) Log.d(TAG, "sent input EOS (with zero-length frame)");
                        } else {
                            ByteBuffer inputBuf = encoderInputBuffers[inputBufIndex];

                            // the buffer should be sized to hold one full frame
                            inputBuf.clear();


                            /**
                             * use a bitmap in resource
                             * */
                            final BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inScaled = false;   // No pre-scaling
                            // Read in the resource
                            synchronized (mFrameSyncObject) {
                                while (!mFrameAvailable) {
                                    try{
                                        final int TIMEOUT_MS = 2500;
                                        mFrameSyncObject.wait(TIMEOUT_MS);
                                        if(!mFrameAvailable){
                                            Log.e(TAG, "Camera frame wait timed out");
                                        }
                                    }catch (Exception e){
                                        e.printStackTrace();
                                    }
                                }
                                mFrameAvailable = false;
                            }
                            if(nv21Data != null){
                                convertor.convert(nv21Data, inputBuf);

                                mEncoder.queueInputBuffer(inputBufIndex, 0, frameData.length, ptsUsec, 0);

                                if (DEBUG) Log.d(TAG, "submitted frame " + generateIndex + " to enc");
                            } else {
                                if (DEBUG) Log.e(TAG, "nv21Data is null");
                            }

                        }
                        generateIndex++;
                    }
                }

                // Check for output from the encoder.  If there's no output yet, we either need to
                // provide more input, or we need to wait for the encoder to work its magic.  We
                // can't actually tell which is the case, so if we can't get an output buffer right
                // away we loop around and see if it wants more input.
                //
                // Once we get EOS from the encoder, we don't need to do this anymore.
                if(!encoderDone){
                    int encoderStatus = mEncoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                    if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // no output available yet
                        if (DEBUG) Log.d(TAG, "no output from encoder available");
//                        break;
                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        // not expected for an encoder
                        encoderOutputBuffers = mEncoder.getOutputBuffers();
                        if (DEBUG) Log.d(TAG, "encoder output buffers changed");
                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // not expected for an encoder
                        MediaFormat newFormat = mEncoder.getOutputFormat();
                        if (DEBUG) Log.d(TAG, "encoder output format changed: " + newFormat);

                        // now that we have the Magic Goodies, start the muxer
                        mMuxer.addTrack(newFormat);
                        mMuxer.start();

                    } else if (encoderStatus < 0) {
                        Log.e(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                    } else { // encoderStatus >= 0
                        ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                        if (encodedData == null) {
                            Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                        }
                        // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                        encodedData.position(info.offset);
                        encodedData.limit(info.offset + info.size);

                        /**
                         * Save to mp4
                         */
                        byte[] data = new byte[info.size];
                        encodedData.get(data);
                        encodedData.position(info.offset);
                        try {
                            encodedData.position(info.offset);
                            encodedData.limit(info.offset + info.size);

                            mMuxer.writeSampleData(0, encodedData, info);
                            Log.d(TAG, "sent " + info.size + " bytes to muxer");

                        } catch (Exception ioe) {
                            Log.w(TAG, "failed writing debug data to file");
                            throw new RuntimeException(ioe);
                        }

                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            // Codec config info.  Only expected on first packet.  One way to
                            // handle this is to manually stuff the data into the MediaFormat
                            // and pass that to configure().  We do that here to exercise the API.

                        } else {
                            encoderDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                            if (DEBUG) Log.d(TAG, "passed " + info.size + " bytes to decoder"
                                    + (encoderDone ? " (EOS)" : ""));
                        }
                        mEncoder.releaseOutputBuffer(encoderStatus, false);
                    } // end else encoderStatus
                }
            }// end while

            if (DEBUG) Log.d(TAG, "---------- end - doGenerateSaveVideo ------------");
        }

        /**
         * Generates the presentation time for frame N, in microseconds.
         */
        private long computePresentationTime(int frameIndex) {
            return 132 + frameIndex * 1000000 / FRAME_RATE;
        }
    }

    /**
     * Helper functions
     */

    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no
     * match was found.
     */
    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    /**
     * Returns a color format that is supported by the codec and by this test code.  If no
     * match is found, this throws a test failure -- the set of formats known to the test
     * should be expanded for new platforms.
     */
    private static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                return colorFormat;
            }
        }
        return 0;   // not reached
    }
    /**
     * Returns true if this is a color format that this test code understands (i.e. we know how
     * to read and generate frames in this format).
     */
    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            // these are the formats we know how to handle for this test
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }
    /**
     * Returns true if the specified color format is semi-planar YUV.  Throws an exception
     * if the color format is not recognized (e.g. not YUV).
     */
    private static boolean isSemiPlanarYUV(int colorFormat) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
                return false;
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                throw new RuntimeException("unknown format " + colorFormat);
        }
    }

    /**
     * Conversion tool
     */
    // untested function
    byte [] getNV21(int inputWidth, int inputHeight, Bitmap scaled, boolean needRecycle) {

        int [] argb = new int[inputWidth * inputHeight];

        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight);

        byte [] yuv = new byte[inputWidth*inputHeight*3/2];
        encodeYUV420SP(yuv, argb, inputWidth, inputHeight);

        if (needRecycle){
            scaled.recycle();
        }
        return yuv;
    }

    void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        final int frameSize = width * height;

        int yIndex = 0;
        int uvIndex = frameSize;

        int a, R, G, B, Y, U, V;
        int index = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {

                a = (argb[index] & 0xff000000) >> 24; // a is not used obviously
                R = (argb[index] & 0xff0000) >> 16;
                G = (argb[index] & 0xff00) >> 8;
                B = (argb[index] & 0xff) >> 0;

                // well known RGB to YUV algorithm
                Y = ( (  66 * R + 129 * G +  25 * B + 128) >> 8) +  16;
                U = ( ( -38 * R -  74 * G + 112 * B + 128) >> 8) + 128;
                V = ( ( 112 * R -  94 * G -  18 * B + 128) >> 8) + 128;

                // NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
                //    meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
                //    pixel AND every other scanline.
                yuv420sp[yIndex++] = (byte) ((Y < 0) ? 0 : ((Y > 255) ? 255 : Y));
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (byte)((V<0) ? 0 : ((V > 255) ? 255 : V));
                    yuv420sp[uvIndex++] = (byte)((U<0) ? 0 : ((U > 255) ? 255 : U));
                }

                index ++;
            }
        }
    }
}