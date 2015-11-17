package com.quan404.record_audio_to_aac;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class MainActivity extends Activity {
    private String TAG = "MainActivity";
    private enum RECORD_STATE {IDLE, START, PROCESSING, STOP, QUIT};
    private RECORD_STATE recordState = RECORD_STATE.IDLE;
    private ReentrantLock recordStateLock = new ReentrantLock();

    private boolean isRecording = false;
    private Button recordBtn;

    private ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(false));
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recordBtn = (Button) findViewById(R.id.recordBtn);
        recordBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isRecording) {
                    stopRecording();
                    recordBtn.setText("RECORD");
                } else {
                    startRecording();
                    recordBtn.setText("STOP");
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        switchState(RECORD_STATE.IDLE);
        try{
            EXECUTOR.execute(new RecordingRunnable());
        }catch (Exception e){
            e.printStackTrace();
        }

    }

    @Override
    protected void onPause() {
        super.onPause();

        switchState(RECORD_STATE.QUIT);
    }

    private void startRecording(){
        switchState(RECORD_STATE.START);

        isRecording = true;
    }

    private void stopRecording(){
        switchState(RECORD_STATE.STOP);

        isRecording = false;
    }
    private class RecordingRunnable implements Runnable{
        private String TAG = "RecordingRunnable";
        private AudioRecord recorder = null;
        private short[] buffer = null;
        private int bufferSize = 0;

        private MediaCodec mEncoder;
        private String MIME_TYPE = "audio/mp4a-latm";
        private ByteBuffer[] inputBuffers;
        private ByteBuffer[] encoderOutputBuffers;

        private MediaMuxer mMuxer;
        @Override
        public void run() {
            while(true){
                RECORD_STATE state = getState();
                switch (state){
                    case START:
                        prepareAudioRecord();
                        prepareMediaCodec();
                        prepareMediaMuxer();
                        switchState(RECORD_STATE.PROCESSING);
                        break;
                    case PROCESSING:
                        // pass data to encoder
                        passDataToEncoder(false);
                        // pass data from encoder to muxer
                        passDataToMuxer();
                        break;
                    case STOP:
                        // send EOS signal
                        passDataToEncoder(true);
                        while(passDataToMuxer() == false);

                        // release AudioRecord
                        releaseAudioRecord();
                        releaseMediaCodec();
                        releaseMediaMuxer();
                        switchState(RECORD_STATE.IDLE);
                        break;
                    case IDLE:
                        break;
                    case QUIT:
                        // if processing -> stop & return
                        return;
                }
            }
        }
        private void prepareAudioRecord(){
            // new AudioRecord
            for (int rate : new int[] {8000, 11025, 16000, 22050, 44100}) {  // add the rates you wish to check against
                int size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (size > 0) {
                    // buffer size is valid, Sample rate supported
                    Log.d(TAG, "Support " + rate);
                    bufferSize = size;
                }else{
                    Log.d(TAG, "Don't Support " + rate);
                }
            }

            buffer = new short[bufferSize];
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            recorder.startRecording();
            buffer = new short[bufferSize];
        }
        private void releaseAudioRecord(){
            if( recorder != null){
                recorder.release();
            }
        }
        private void prepareMediaCodec(){
            try {

                MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
                if (codecInfo == null) {
                    // Don't fail CTS if they don't have an AVC codec (not here, anyway).
                    Log.e(TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
                    return;
                }
                Log.d(TAG, "found codec: " + codecInfo.getName());

                // We avoid the device-specific limitations on width and height by using values that
                // are multiples of 16, which all tested devices seem to be able to handle.

                MediaFormat format = new MediaFormat();
                format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
                format.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, 8000);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize);

                Log.d(TAG, "format: " + format);
                // Create a MediaCodec for the desired codec, then configure it as an encoder with
                // our desired properties.
                mEncoder = MediaCodec.createByCodecName(codecInfo.getName());
                mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                mEncoder.start();

                inputBuffers = mEncoder.getInputBuffers();
                encoderOutputBuffers  = mEncoder.getOutputBuffers();

            } catch (Exception e){
                e.printStackTrace();
            }
        }
        private void passDataToEncoder(boolean inputDone){
            int bufferIndex = mEncoder.dequeueInputBuffer(10000);
            if (bufferIndex>=0) {
                inputBuffers[bufferIndex].clear();
                // read audio data
                int len = recorder.read(inputBuffers[bufferIndex], bufferSize);

                if (len == AudioRecord.ERROR_INVALID_OPERATION || len == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "An error occured with the AudioRecord API !");
                } else {
                    Log.v(TAG, "Pushing raw audio to the decoder: len=" + len + " bs: " + inputBuffers[bufferIndex].capacity() + " inputDone = " + inputDone);
                    if(inputDone){
                        mEncoder.queueInputBuffer(bufferIndex, 0, 0, System.nanoTime() / 1000,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                        Log.d(TAG, "sent input EOS (with zero-length frame)");
                    }else{
                        mEncoder.queueInputBuffer(bufferIndex, 0, len, System.nanoTime() / 1000, 0);
                    }
                }
            }
        }

        private void releaseMediaCodec(){
            if (mEncoder != null) {
                mEncoder.stop();
                mEncoder.release();
            }
        }
        private void prepareMediaMuxer(){
            File OUTPUT_DIR = new File(Environment.getExternalStorageDirectory() + "/");
            String outputPath = new File(OUTPUT_DIR, "testAudio.mp4").toString();
            Log.i(TAG, "Output file is " + outputPath);

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
        private boolean passDataToMuxer(){
            boolean encoderDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int encoderStatus = mEncoder.dequeueOutputBuffer(info, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                Log.d(TAG, "no output from encoder available");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.getOutputBuffers();
                Log.d(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // not expected for an encoder
                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // init muxer
                mMuxer.addTrack(newFormat);
                mMuxer.start();
            } else if (encoderStatus < 0) {
                Log.e(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];

                if (encodedData == null) {
                    Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                }else{
                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    encodedData.position(info.offset);
                    encodedData.limit(info.offset + info.size);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Codec config info.  Only expected on first packet.  One way to
                        // handle this is to manually stuff the data into the MediaFormat
                        // and pass that to configure().  We do that here to exercise the API.
                    } else {
                        encoderDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        Log.d(TAG, "passed " + info.size + " bytes to decoder"
                                + (encoderDone ? " (EOS)" : ""));

                        // pass to muxer
                        if(encoderDone){
                            info.presentationTimeUs = 0;
                            info.size = 0;
                            info.offset = 0;
                        }
                        Log.d(TAG, "before sent " + info.size + " bytes to muxer info.presentationTimeUs = " + info.presentationTimeUs + " info.size = " + info.size + " info.offset = " + info.offset);
                        mMuxer.writeSampleData(0, encodedData, info);
                        Log.d(TAG, "sent " + info.size + " bytes to muxer " + info.presentationTimeUs);
                    }
                    mEncoder.releaseOutputBuffer(encoderStatus, false);
                }
            } // end else encoderStatus

            return encoderDone;
        }
        private void releaseMediaMuxer(){
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
    }
    private void switchState(RECORD_STATE t){
        recordStateLock.lock();
        Log.d(TAG, "switchState From " + recordState.toString() + " to " + t.toString());
        recordState = t;
        recordStateLock.unlock();
    }
    private RECORD_STATE getState(){
        RECORD_STATE t;
        recordStateLock.lock();
        t = recordState;
        recordStateLock.unlock();

        return t;
    }
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
}
