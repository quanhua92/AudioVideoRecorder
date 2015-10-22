package com.quan404.cloneaudiovideofromsource;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * Clone video+audio track from input to output using MediaMuxer
 *
 * Required: Android 4.3, API 18 or above
 *
 * Reference: https://android.googlesource.com/platform/cts/+/kitkat-release/tests/tests/media/src/android/media/cts/MediaMuxerTest.java
 */
public class MainActivity extends Activity {
    private static final String TAG = "Clone_AV_Activity";
    private static final boolean VERBOSE = true;

    private static final int MAX_SAMPLE_SIZE = 256 * 1024;

    /**
     * Get the sdcard path.
     * Permission: WRITE_EXTERNAL_STORAGE
     */
    private File OUTPUT_DIR = Environment.getExternalStorageDirectory();

    private Resources mResources;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mResources = getResources();

        findViewById(R.id.cloneAVBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "Clone Audio Video Button Clicked");
                Toast.makeText(getApplicationContext(), "Clone Audio Video from input to output", Toast.LENGTH_SHORT).show();

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        int source = R.raw.video_176x144_3gp_h263_300kbps_25fps_aac_stereo_128kbps_11025hz;
                        final String outputFile = OUTPUT_DIR + "/videoAudio.mp4";
                        Log.d(TAG, "outputFile : " + outputFile);
                        try {
                            cloneMediaUsingMuxer(source, outputFile, 2, 180);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(), "Cloned and rotated 180 degree. Saved to sdcard: " + outputFile, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        });
    }

    /**
     * Clone a media file using MediaMuxer
     */
    private void cloneMediaUsingMuxer(int srcMedia, String dstMediaPath,
                                      int expectedTrackCount, int degrees) throws IOException {
        // Set up MediaExtractor to read from the source.
        AssetFileDescriptor srcFd = mResources.openRawResourceFd(srcMedia);
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(srcFd.getFileDescriptor(), srcFd.getStartOffset(),
                srcFd.getLength());
        int trackCount = extractor.getTrackCount();

        // Set up MediaMuxer for the destination.
        MediaMuxer muxer;
        muxer = new MediaMuxer(dstMediaPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        // Set up the tracks.
        HashMap<Integer, Integer> indexMap = new HashMap<Integer, Integer>(trackCount);
        for (int i = 0; i < trackCount; i++) {
            extractor.selectTrack(i);
            MediaFormat format = extractor.getTrackFormat(i);
            int dstIndex = muxer.addTrack(format);
            indexMap.put(i, dstIndex);
        }
        // Copy the samples from MediaExtractor to MediaMuxer.
        boolean sawEOS = false;
        int bufferSize = MAX_SAMPLE_SIZE;
        int frameCount = 0;
        int offset = 100;
        ByteBuffer dstBuf = ByteBuffer.allocate(bufferSize);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        if (degrees >= 0) {
            muxer.setOrientationHint(degrees);
        }
        muxer.start();
        while (!sawEOS) {
            bufferInfo.offset = offset;
            bufferInfo.size = extractor.readSampleData(dstBuf, offset);
            if (bufferInfo.size < 0) {
                if (VERBOSE) {
                    Log.d(TAG, "saw input EOS.");
                }
                sawEOS = true;
                bufferInfo.size = 0;
            } else {
                bufferInfo.presentationTimeUs = extractor.getSampleTime();
                bufferInfo.flags = extractor.getSampleFlags();
                int trackIndex = extractor.getSampleTrackIndex();
                muxer.writeSampleData(indexMap.get(trackIndex), dstBuf,
                        bufferInfo);
                extractor.advance();
                frameCount++;
                if (VERBOSE) {
                    Log.d(TAG, "Frame (" + frameCount + ") " +
                            "PresentationTimeUs:" + bufferInfo.presentationTimeUs +
                            " Flags:" + bufferInfo.flags +
                            " TrackIndex:" + trackIndex +
                            " Size(KB) " + bufferInfo.size / 1024);
                }
            }
        }
        muxer.stop();
        muxer.release();
        srcFd.close();
        return;
    }
}
