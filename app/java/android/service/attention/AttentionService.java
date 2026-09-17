package android.service.attention;

import android.app.Service;

/**
 * Compile-time stub of the framework AttentionService (not in public SDK jar).
 * The real class lives on the device boot classpath and shadows this copy at runtime;
 * only signatures matter here. Signatures match the on-device framework (callback-object
 * style, see aon_ulp_probe/README.md).
 */
public abstract class AttentionService extends Service {
    public static final String SERVICE_INTERFACE = "android.service.attention.AttentionService";

    public static final int ATTENTION_SUCCESS_ABSENT = 0;
    public static final int ATTENTION_SUCCESS_PRESENT = 1;
    public static final int ATTENTION_FAILURE_UNKNOWN = 2;
    public static final int ATTENTION_FAILURE_CANCELLED = 3;
    public static final int ATTENTION_FAILURE_PREEMPTED = 4;
    public static final int ATTENTION_FAILURE_TIMED_OUT = 5;
    public static final int ATTENTION_FAILURE_CAMERA_PERMISSION_ABSENT = 6;

    public abstract void onCheckAttention(AttentionCallback callback);

    public abstract void onCancelAttentionCheck(AttentionCallback callback);

    @Override
    public android.os.IBinder onBind(android.content.Intent intent) {
        return null; // runtime uses the real framework implementation
    }

    public static final class AttentionCallback {
        public void onSuccess(int result, long timestamp) {}

        public void onFailure(int error) {}
    }
}
