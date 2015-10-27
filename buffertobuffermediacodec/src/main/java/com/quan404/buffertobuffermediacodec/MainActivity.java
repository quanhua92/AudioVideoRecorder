package com.quan404.buffertobuffermediacodec;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

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

        private int mWidth = 320;
        private int mHeight = 240;
        private int mBitrate = 2000000;

        public EncodingThread(int width, int height, int bitrate){
            if (DEBUG) Log.d(TAG, "Constructor");

            mWidth = width;
            mHeight = height;
            mBitrate = bitrate;
        }
        @Override
        public void run() {
            Log.d(TAG, "Start encoding");
            /**
             * Generate video and save to sdcard
             */


            /**
             * Finish Thread
             */
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "Finish: saved video to sdcard", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * Helper functions
     */
}
