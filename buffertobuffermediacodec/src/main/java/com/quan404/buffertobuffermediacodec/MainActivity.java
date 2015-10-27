package com.quan404.buffertobuffermediacodec;

import android.app.Activity;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Buffer-to-buffer. Buffers are software-generated YUV frames in ByteBuffer objects,
 * and decoded to the same. This is the slowest (and least portable) approach,
 * but it allows the application to examine and modify the YUV data.
 * Reference: http://bigflake.com/mediacodec/
 */
public class MainActivity extends Activity {

    /**
     * UI Stuffs
     */
    private Button encodeBtn;

    private static File OUTPUT_DIR = Environment.getExternalStorageDirectory();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        /**
         * UI Stuffs
         */
        encodeBtn = (Button) findViewById(R.id.encode_video);
        encodeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Toast.makeText(getApplicationContext(), "Start encoding", Toast.LENGTH_SHORT).show();

                new Thread(new EncodingThread(320, 240, 2000000)).start();
            }
        });
    }

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

        private int mWidth;
        private int mHeight;
        private int mBitRate;

        /**
         * MediaMuxer
         */
        private MediaMuxer mMuxer;

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
            String outputPath = new File(OUTPUT_DIR,
                    "test.mp4").toString();
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

        private void prepareEncoder(){
            try {
                MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
                if (codecInfo == null) {
                    // Don't fail CTS if they don't have an AVC codec (not here, anyway).
                    if (DEBUG) Log.e(TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
                    return;
                }
                if (DEBUG) Log.d(TAG, "found codec: " + codecInfo.getName());
                int colorFormat = selectColorFormat(codecInfo, MIME_TYPE);
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
            int checkIndex = 0;

            // The size of a frame of video data, in the formats we handle, is stride*sliceHeight
            // for Y, and (stride/2)*(sliceHeight/2) for each of the Cb and Cr channels.  Application
            // of algebra and assuming that stride==width and sliceHeight==height yields:
            byte[] frameData = new byte[mWidth * mHeight * 3 / 2];

            if (DEBUG) Log.d(TAG, "---------- end - doGenerateSaveVideo ------------");
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
}
