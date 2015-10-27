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
import java.util.Arrays;

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
        private int DURATION_SEC = 5;             // 8 seconds of video

        private int mWidth;
        private int mHeight;
        private int mBitRate;
        private int colorFormat;
        /**
         * MediaMuxer
         */
        private MediaMuxer mMuxer;


        /**
         * Test Data
         */
        private static final int TEST_Y = 120;                  // YUV values for colored rect
        private static final int TEST_U = 160;
        private static final int TEST_V = 200;
        private static final int TEST_R0 = 0;                   // RGB equivalent of {0,0,0}
        private static final int TEST_G0 = 136;
        private static final int TEST_B0 = 0;
        private static final int TEST_R1 = 236;                 // RGB equivalent of {120,160,200}
        private static final int TEST_G1 = 50;
        private static final int TEST_B1 = 186;

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
                    int inputBufIndex = mEncoder.dequeueInputBuffer(TIMEOUT_USEC);
                    if (DEBUG) Log.d(TAG, "inputBufIndex=" + inputBufIndex);

                    if (inputBufIndex >= 0) {
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
                            generateFrame(generateIndex, colorFormat, frameData);
                            ByteBuffer inputBuf = encoderInputBuffers[inputBufIndex];
                            // the buffer should be sized to hold one full frame
                            inputBuf.clear();
                            inputBuf.put(frameData);
                            mEncoder.queueInputBuffer(inputBufIndex, 0, frameData.length, ptsUsec, 0);
                            if (DEBUG) Log.d(TAG, "submitted frame " + generateIndex + " to enc");
                        }
                        generateIndex++;
                    } else {
                        // either all in use, or we timed out during initial setup
                        if (DEBUG) Log.d(TAG, "input buffer not available");
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

        /**
         * Generates data for frame N into the supplied buffer.  We have an 8-frame animation
         * sequence that wraps around.  It looks like this:
         * <pre>
         *   0 1 2 3
         *   7 6 5 4
         * </pre>
         * We draw one of the eight rectangles and leave the rest set to the zero-fill color.
         */
        private void generateFrame(int frameIndex, int colorFormat, byte[] frameData) {
            final int HALF_WIDTH = mWidth / 2;
            boolean semiPlanar = isSemiPlanarYUV(colorFormat);
            // Set to zero.  In YUV this is a dull green.
            Arrays.fill(frameData, (byte) 0);
            int startX, startY, countX, countY;
            frameIndex %= 8;
            //frameIndex = (frameIndex / 8) % 8;    // use this instead for debug -- easier to see
            if (frameIndex < 4) {
                startX = frameIndex * (mWidth / 4);
                startY = 0;
            } else {
                startX = (7 - frameIndex) * (mWidth / 4);
                startY = mHeight / 2;
            }
            for (int y = startY + (mHeight/2) - 1; y >= startY; --y) {
                for (int x = startX + (mWidth/4) - 1; x >= startX; --x) {
                    if (semiPlanar) {
                        // full-size Y, followed by UV pairs at half resolution
                        // e.g. Nexus 4 OMX.qcom.video.encoder.avc COLOR_FormatYUV420SemiPlanar
                        // e.g. Galaxy Nexus OMX.TI.DUCATI1.VIDEO.H264E
                        //        OMX_TI_COLOR_FormatYUV420PackedSemiPlanar
                        frameData[y * mWidth + x] = (byte) TEST_Y;
                        if ((x & 0x01) == 0 && (y & 0x01) == 0) {
                            frameData[mWidth*mHeight + y * HALF_WIDTH + x] = (byte) TEST_U;
                            frameData[mWidth*mHeight + y * HALF_WIDTH + x + 1] = (byte) TEST_V;
                        }
                    } else {
                        // full-size Y, followed by quarter-size U and quarter-size V
                        // e.g. Nexus 10 OMX.Exynos.AVC.Encoder COLOR_FormatYUV420Planar
                        // e.g. Nexus 7 OMX.Nvidia.h264.encoder COLOR_FormatYUV420Planar
                        frameData[y * mWidth + x] = (byte) TEST_Y;
                        if ((x & 0x01) == 0 && (y & 0x01) == 0) {
                            frameData[mWidth*mHeight + (y/2) * HALF_WIDTH + (x/2)] = (byte) TEST_U;
                            frameData[mWidth*mHeight + HALF_WIDTH * (mHeight / 2) +
                                    (y/2) * HALF_WIDTH + (x/2)] = (byte) TEST_V;
                        }
                    }
                }
            }
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
}
