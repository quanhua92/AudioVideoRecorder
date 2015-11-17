package com.quan404.record_audio_to_aac;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

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
                } else {
                    startRecording();
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
        @Override
        public void run() {
            while(true){
                RECORD_STATE state = getState();
                switch (state){
                    case START:
                        // new AudioRecord
                        switchState(RECORD_STATE.PROCESSING);
                        break;
                    case PROCESSING:
                        // save byte data
                        break;
                    case STOP:
                        // release AudioRecord
                        switchState(RECORD_STATE.IDLE);
                        break;
                    case IDLE:
                        break;
                    case QUIT:
                        // if processing -> stop & return
                        return;
                }

                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
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
}
