package com.dzshzx.zeta.probe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;

/**
 * 整屏一个字：数单击、双击和拖动（长按拿起再挪、松手）。字随时更新，Zeta 的控件列表里看得到；启动时在 files/ 里留一个文件，
 * 清除数据后它就没了。
 */
public class ProbeActivity extends Activity {
    private int taps, doubles, drags, dy;
    private boolean dragging;
    private float downY;
    private TextView text;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "opened.txt"))) {
            out.write('1');
        } catch (Exception e) {
            Log.w("ZetaProbe", "write", e);
        }
        text = new TextView(this);
        text.setTextSize(28);
        text.setGravity(Gravity.CENTER);
        setContentView(text);
        final GestureDetector detector =
                new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        taps++;
                        show();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        doubles++;
                        show();
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        Log.i("ZetaProbe", "long press t=" + (e.getEventTime() - e.getDownTime()));
                        dragging = true;
                        downY = e.getY();
                    }
                });
        text.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int action = e.getActionMasked();
                if (action != MotionEvent.ACTION_MOVE || e.getHistorySize() == 0)
                    Log.i("ZetaProbe", "touch " + MotionEvent.actionToString(action) + " t="
                            + (e.getEventTime() - e.getDownTime()) + " y=" + Math.round(e.getY()));
                detector.onTouchEvent(e);
                if (dragging && action == MotionEvent.ACTION_UP) {
                    drags++;
                    dy = Math.round(e.getY() - downY);
                    show();
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) dragging = false;
                return true;
            }
        });
        show();
    }

    private void show() {
        String line = "probe taps=" + taps + " doubles=" + doubles + " drags=" + drags + " dy=" + dy;
        text.setText(line);
        Log.i("ZetaProbe", line);
    }
}
