import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Looper;

public class AonProbe {
    static final String DESC = "vendor.qti.hardware.camera.aon.IAONService/default";
    static IBinder svc;

    static void log(String s) { System.out.println("[AON] " + s); }

    static Parcel obtain() { return Parcel.obtain(); }

    // Patch-style header used by NDK AIDL for parcelable structs:
    // writeInt32(0); ... fields ...; patch header with final absolute data position.
    static int writeHeader(Parcel p) { int pos = p.dataPosition(); p.writeInt(0); return pos; }
    static void patchHeader(Parcel p, int headerPos) {
        int cur = p.dataPosition();
        p.setDataPosition(headerPos);
        p.writeInt(cur);
        p.setDataPosition(cur);
    }

    static void dumpParcel(Parcel p) {
        int start = p.dataPosition();
        int n = 0;
        StringBuilder sb = new StringBuilder();
        while (p.dataAvail() > 0 && n < 200) {
            int v = p.readInt();
            float f = Float.intBitsToFloat(v);
            String extra = (v > 100 && v < 10_000_000) ? "" : String.format(" (f=%g ascii=%s)", f, printable(v));
            sb.append(v).append(extra).append(' ');
            n++;
        }
        log("parcel ints: " + sb);
        p.setDataPosition(start);
    }

    static String printable(int v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            int c = (v >>> (24 - i * 8)) & 0xff;
            sb.append(c >= 0x20 && c < 0x7f ? (char) c : '.');
        }
        return sb.toString();
    }

    // Query only: transaction 1 = GetAONSensorInfoList
    static void query() throws Exception {
        Parcel data = obtain(), reply = obtain();
        try {
            data.writeInterfaceToken(DESC);
            log("token written; pingBinder=" + svc.pingBinder() + " iface=" + svc.getInterfaceDescriptor());
            boolean ok = svc.transact(1, data, reply, 0);
            log("transact(1 GetAONSensorInfoList) ok=" + ok);
            if (!ok) return;
            reply.readException();
            log("no exception; reply:");
            dumpParcel(reply);
        } finally { data.recycle(); reply.recycle(); }
    }

    // Register: transaction 2 = RegisterClient(cb, AONRegisterInfo, out long)
    static void register(int serviceType, int sensorIdx, int[] fdInfo) throws Exception {
        Parcel data = obtain(), reply = obtain();
        try {
            data.writeInterfaceToken(DESC);
            data.writeStrongBinder(new BinderCb());
            data.writeInt(1); // AONRegisterInfo presence
            int h = writeHeader(data);
            data.writeInt(serviceType);      // field1: AONServiceType
            data.writeInt(sensorIdx);        // field2: sensor/aon index
            data.writeInt(1);                // FDRegisterInfo present
            int hf = writeHeader(data);
            for (int v : fdInfo) data.writeInt(v);
            patchHeader(data, hf);
            data.writeInt(0);                // QRRegisterInfo absent
            data.writeInt(0);                // HDRegisterInfo absent
            data.writeInt(0);                // GDRegisterInfo absent
            patchHeader(data, h);

            boolean ok = svc.transact(2, data, reply, 0);
            log("transact(2 RegisterClient) ok=" + ok);
            if (!ok) return;
            reply.readException();
            if (reply.dataAvail() > 0) {
                log("clientId=" + reply.readLong());
                if (reply.dataAvail() > 0) dumpParcel(reply);
            } else log("empty reply");
        } finally { data.recycle(); reply.recycle(); }
    }

    static class BinderCb extends android.os.Binder {
        @Override protected boolean onTransact(int code, Parcel d, Parcel r, int flags) {
            log("CALLBACK code=" + code);
            if (d != null) { d.setDataPosition(0); dumpParcel(d); }
            try { r.writeNoException(); } catch (Exception e) {}
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        log("arg0=" + (args.length > 0 ? args[0] : "<none>"));
        Object raw = null;
        try {
            raw = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, DESC);
        } catch (Throwable t) { log("reflect err: " + t); }
        log("raw=" + raw);
        svc = (IBinder) raw;
        log("service binder = " + svc);
        if (svc == null) return;

        query();

        if (args.length >= 7) {
            int st = Integer.parseInt(args[0]);
            int idx = Integer.parseInt(args[1]);
            int[] fd = new int[5];
            for (int i = 0; i < 5; i++) fd[i] = Integer.parseInt(args[2 + i]);
            int waitMs = Integer.parseInt(args[7]);
            log(String.format("register serviceType=%d sensorIdx=%d fdInfo=%s wait=%dms",
                st, idx, java.util.Arrays.toString(fd), waitMs));
            register(st, idx, fd);
            long end = System.currentTimeMillis() + waitMs;
            while (System.currentTimeMillis() < end) { Thread.sleep(500); }
        }
        log("done");
        System.exit(0);
    }
}
