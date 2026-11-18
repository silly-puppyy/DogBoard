import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Replays recorded finger paths as touch events on a device or emulator (tools/swipe/replay, run by replay.py):
 *   CLASSPATH=/data/local/tmp/replay.dex app_process / Replay FILE
 * FILE: one stroke per line, "x,y,t x,y,t ..." in screen pixels and ms (t from the stroke's start). The stroke
 * is sent with its own timing (sleeping between points), then the program prints "done" and waits for a line on
 * stdin before the next stroke, so the caller can read the text field in between. Uses the hidden
 * InputManagerGlobal.injectInputEvent (the shell user may inject).
 */
public class Replay {
    public static void main(String[] args) throws Exception {
        Object im = Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null);
        Method inject = im.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        BufferedReader in = new BufferedReader(new java.io.InputStreamReader(System.in));
        // a real-looking finger: tool type finger, pressure and size, from the touchscreen device (Gboard reads a bare
        // MotionEvent.obtain pointer, tool type unknown, as taps only: 2026-11-19, every swipe came out as one letter)
        int device = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) { device = id; break; }
        }
        MotionEvent.PointerProperties[] props = { new MotionEvent.PointerProperties() };
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
        BufferedReader f = new BufferedReader(new FileReader(args[0]));
        String line;
        while ((line = f.readLine()) != null) {
            String[] pts = line.trim().split(" ");
            ArrayList<float[]> p = new ArrayList<>();
            for (String s : pts) {
                String[] v = s.split(",");
                p.add(new float[]{Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2])});
            }
            long down = SystemClock.uptimeMillis();
            for (int i = 0; i < p.size(); i++) {
                float[] q = p.get(i);
                long at = down + (long) q[2];
                long now = SystemClock.uptimeMillis();
                if (at > now) Thread.sleep(at - now);
                int action = i == 0 ? MotionEvent.ACTION_DOWN : i == p.size() - 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                coords[0].clear();
                coords[0].x = q[0]; coords[0].y = q[1]; coords[0].pressure = 1f; coords[0].size = 0.05f;
                coords[0].touchMajor = 40f; coords[0].touchMinor = 40f;
                MotionEvent e = MotionEvent.obtain(down, Math.max(at, SystemClock.uptimeMillis()), action, 1, props, coords,
                        0, 0, 1f, 1f, device, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                inject.invoke(im, e, 0);
                e.recycle();
            }
            System.out.println("done");
            System.out.flush();
            if (in.readLine() == null) break;
        }
    }
}
