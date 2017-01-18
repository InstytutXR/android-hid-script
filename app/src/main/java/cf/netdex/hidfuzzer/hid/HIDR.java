package cf.netdex.hidfuzzer.hid;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

import cf.netdex.hidfuzzer.MainActivity;
import eu.chainfire.libsuperuser.Shell;

/**
 * Wrapper for HID class for ease of usage
 *
 * Created by netdex on 1/16/2017.
 */

public class HIDR {
    private Shell.Interactive mSU;
    private String mDevKeyboard;
    private String mDevMouse;

    private KeyboardLightListener mKeyboardLightListener;

    public HIDR(Shell.Interactive su, String devKeyboard, String devMouse) {
        this.mSU = su;
        this.mDevKeyboard = devKeyboard;
        this.mDevMouse = devMouse;
        this.mKeyboardLightListener = new KeyboardLightListener();
    }

    public void delay(long m) {
        try {
            Thread.sleep(m);
        } catch (InterruptedException ignored) {
        }
    }

    public int test(){
        return hid_keyboard((byte) 0, Input.KB.K.VOLUME_UP.c);
    }

    public int hid_mouse(byte... offset) {
        return HID.hid_mouse(mSU, mDevMouse, offset);
    }

    public int hid_keyboard(byte... keys) {
        return HID.hid_keyboard(mSU, mDevKeyboard, keys);
    }

    public int press_keys(byte... keys) {
        int ec = 0;
        ec |= hid_keyboard(keys);
        ec |= hid_keyboard();
        return ec;
    }

    /* Begin string to c conversion tables */
    private static final String MP_ALPHA = "abcdefghijklmnopqrstuvwxyz";        // 0x04
    private static final String MP_ALPHA_ALT = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";    // 0x04 SHIFT
    private static final String MP_NUM = "1234567890";                          // 0x1E
    private static final String MP_NUM_ALT = "!@#$%^&*()";                      // 0x1E SHIFT
    private static final String MP_SPEC = " -=[]\\#;'`,./";                     // 0x2C
    private static final String MP_SPEC_ALT = " _+{}| :\"~<>?";                 // 0x2C SHIFT
    private static final String MP_SU_SPEC = "\n";                              // 0X28

    private static final String[] AP_ATT = {MP_ALPHA, MP_ALPHA_ALT, MP_NUM, MP_NUM_ALT, MP_SPEC, MP_SPEC_ALT, MP_SU_SPEC};
    private static final boolean[] AP_SHIFT = {false, true, false, true, false, true, false};
    private static final byte[] AP_OFFSET = {0x04, 0x04, 0x1E, 0x1E, 0x2C, 0x2C, 0x28};

    private static final byte[] AP_MAP_CODE = new byte[128];
    private static final boolean[] AP_MAP_SHIFT = new boolean[128];

    // build fast conversion tables from human readable data
    static {
        for (int i = 0; i < 128; i++) {
            char c = (char) i;
            boolean shift = false;
            byte code = 0;

            int idx = 0;
            while (idx < AP_ATT.length) {
                int tc;
                if ((tc = AP_ATT[idx].indexOf(c)) != -1) {
                    code = (byte) (AP_OFFSET[idx] + tc);
                    shift = AP_SHIFT[idx];
                    break;
                }
                idx++;
            }
            if (idx == AP_ATT.length) {
                AP_MAP_CODE[i] = -1;
            } else {
                AP_MAP_CODE[i] = code;
                AP_MAP_SHIFT[i] = shift;
            }
        }
    }
    /* End string to c conversion tables */

    public int send_string(String s) {
        return send_string(s, 0);
    }

    public int send_string(String s, int d) {
        int ec = 0;
        char lc = Character.MIN_VALUE;
        for (char c : s.toCharArray()) {
            byte cd = AP_MAP_CODE[(int) c];
            boolean st = AP_MAP_SHIFT[(int) c];
            if (cd == -1)
                throw new IllegalArgumentException("Given string contains illegal characters");
            if (c == lc)
                ec |= hid_keyboard();
            ec |= hid_keyboard(st ? Input.KB.M.LSHIFT.c : 0, cd);
            if (d != 0)
                delay(d);
            lc = c;
        }
        ec |= hid_keyboard();
        return ec;
    }

    public KeyboardLightListener getKeyboardLightListener() {
        return mKeyboardLightListener;
    }

    public class KeyboardLightListener {
        private Process mKeyboardLightProc;
        private InputStream mKeyboardLightStream;
        private int mLastLightState;

        public int start() {
            if (mKeyboardLightProc != null)
                throw new IllegalArgumentException("KB light proc already running");

            try {
                mKeyboardLightProc = Runtime.getRuntime().exec("cat " + mDevKeyboard);
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (mKeyboardLightProc != null) {
                mKeyboardLightStream = mKeyboardLightProc.getInputStream();
                return 0;
            } else {
                return 1;
            }
        }

        /**
         * Bitmask of light states:
         * NUM      0x01
         * CAPS     0x02
         * SCROLL   0x04
         *
         * @return bitmask of light states
         */
        public int read() {
            try {
                if (mKeyboardLightStream != null)
                    return mLastLightState = mKeyboardLightStream.read();
                return -1;
            } catch (IOException e) {
                Log.d(MainActivity.TAG, "Light stream forcibly terminated");
                return -1;
            }
        }

        public int available() {
            if (mKeyboardLightStream != null) {
                try {
                    return mKeyboardLightStream.available();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return -1;
        }

        public void kill() {
            // HACK don't stare at this for too long, or your eyes will burn out
//                Field f = mKeyboardLightProc.getClass().getDeclaredField("pid");
//                f.setAccessible(true);
//                long pid = f.getLong(mKeyboardLightProc);
//                f.setAccessible(false);
//                String cmd = "pkill -KILL -P " + pid;
//                Log.d("A", cmd);
//                mSU.addCommand(cmd, 0, new Shell.OnCommandLineListener() {
//                    @Override
//                    public void onCommandResult(int commandCode, int exitCode) {
//                        Log.d("A", commandCode + " " + exitCode);
//                    }
//
//                    @Override
//                    public void onLine(String line) {
//                        Log.d("A", line);
//                    }
//                });
            if (mKeyboardLightStream != null) {
                try {
                    mKeyboardLightStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mKeyboardLightStream = null;
            }

            // close the stream before killing the process
            if (mKeyboardLightProc != null) {
                mKeyboardLightProc.destroy();
                mKeyboardLightProc = null;
            }
        }

        public int getLastLightState() {
            return mLastLightState;
        }
    }
}