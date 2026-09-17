package futureharmony.tb522fu.aon

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.lang.reflect.Method
import java.util.Arrays

/**
 * Hand-written Parcel client for vendor.qti.hardware.camera.aon.IAONService.
 * The wire format mirrors the validated native probe byte-for-byte
 * (see TB522FU_Attention_KeepOn/aon_ulp_probe/README.md):
 *
 *   transact 1 = GetAONSensorInfoList
 *   transact 2 = RegisterClient(callbackBinder, presence=1, AONRegisterInfo)
 *   transact 3 = UnregisterClient(int64 clientId)
 *   AONRegisterInfo = [lenPlaceholder patched to end absPos]
 *                     [aonCamIdx][serviceType]
 *                     FD branch:  [present=1][lenPh][mask][algo][w][h][dps]
 *                     QR/HD/GD:   [present=0]
 *   callback (oneway, code 1) = [int64 clientId][pres][len][f0][f1]
 *                     branches (FD, FDPro, QR, HD, GD): [len][len/4 ints]
 *                     FDPro (b==1) payload: v[0] = EvtTypeMask
 *   replies start with an int32 0 status header, compatible with readException().
 */
class AonHalClient {
    interface EventListener {
        /** mask = FDPro payload EvtTypeMask (0x1 present, 0x4 gazing); values = raw ints. */
        fun onAonEvent(clientId: Long, serviceType: Int, mask: Int, values: IntArray?)
    }

    private var service: IBinder? = null
    private var clientId = -1L

    @Volatile private var listener: EventListener? = null

    private val callbackBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                data.setDataPosition(0)
                val cid = data.readLong()
                val pres = data.readInt()
                val len = data.readInt()
                val f0 = data.readInt()
                val f1 = data.readInt()
                var fdPro: IntArray? = null
                var b = 0
                while (b < 5 && data.dataAvail() >= 4) {
                    val bl = data.readInt()
                    if (bl <= 0) {
                        b++
                        continue
                    }
                    val n = bl / 4
                    val vals = IntArray(n)
                    var i = 0
                    while (i < n && data.dataAvail() >= 4) {
                        vals[i] = data.readInt()
                        i++
                    }
                    if (b == 1) fdPro = vals
                    b++
                }
                val mask = if (fdPro != null && fdPro.isNotEmpty()) fdPro[0] else 0
                AonLog.d(TAG, "event cid=$cid pres=$pres len=$len f0=$f0 mask=0x" +
                    Integer.toHexString(mask))
                listener?.onAonEvent(cid, f0, mask, fdPro)
            } catch (t: Throwable) {
                AonLog.e(TAG, "callback parse failed", t)
            }
            reply?.writeNoException()
            return true
        }
    }

    /** Resolve the vendor HAL binder; uses reflection (hidden API) with a VMRuntime
     *  exemptions fallback for hidden-API enforcement. */
    @Throws(Exception::class)
    private fun getService(): IBinder {
        service?.let { return it }
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val m: Method = sm.getMethod("getService", String::class.java)
            val b = m.invoke(null, IFACE) as IBinder
            service = b
            AonLog.i(TAG, "getService direct ok: $b")
            return b
        } catch (direct: Throwable) {
            AonLog.w(TAG, "direct getService blocked ($direct), trying exemptions")
        }
        // double-reflection: Class.class.getDeclaredMethod is SDK-visible; use it to fetch
        // VMRuntime.setHiddenApiExemptions and exempt our own process.
        val gdm = Class::class.java.getDeclaredMethod(
            "getDeclaredMethod", String::class.java, arrayOfNulls<Class<*>>(0).javaClass
        )
        val vmrt = Class.forName("dalvik.system.VMRuntime")
        val setExemptions = gdm.invoke(vmrt, "setHiddenApiExemptions",
            arrayOf<String>().javaClass) as Method
        val getRuntime = gdm.invoke(vmrt, "getRuntime") as Method
        val runtime = getRuntime.invoke(null)
        setExemptions.invoke(runtime, arrayOf("L"))
        val sm = Class.forName("android.os.ServiceManager")
        val m = sm.getMethod("getService", String::class.java)
        val b = m.invoke(null, IFACE) as IBinder
        service = b
        AonLog.i(TAG, "getService via exemptions ok: $b")
        return b
    }

    val isRegistered: Boolean get() = clientId != -1L

    /** Register an FD client; returns clientId on success, -1 on failure. */
    @Synchronized
    fun register(
        camIdx: Int, serviceType: Int, mask: Int, algoIdx: Int,
        w: Int, h: Int, dps: Int, l: EventListener?,
    ): Long {
        listener = l
        try {
            val b = getService()
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(IFACE)
                data.writeStrongBinder(callbackBinder)
                data.writeInt(1) // AONRegisterInfo present
                val hdr = mark(data)
                data.writeInt(camIdx)
                data.writeInt(serviceType)
                // FD branch (the one og0ve supports via FDPRO)
                data.writeInt(1)
                val fd = mark(data)
                data.writeInt(mask)
                data.writeInt(algoIdx)
                data.writeInt(w)
                data.writeInt(h)
                data.writeInt(dps)
                patch(data, fd)
                data.writeInt(0) // QR absent
                data.writeInt(0) // HD absent
                data.writeInt(0) // GD absent
                patch(data, hdr)
                val ok = b.transact(TX_REGISTER, data, reply, 0)
                if (!ok) {
                    AonLog.w(TAG, "register transact returned false")
                    return -1
                }
                reply.readException()
                val cid = reply.readLong()
                clientId = cid
                AonLog.i(TAG, "registered clientId=$cid camIdx=$camIdx" +
                    " serviceType=$serviceType mask=0x" + Integer.toHexString(mask) +
                    " algo=$algoIdx ${w}x$h dps=$dps")
                return cid
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (t: Throwable) {
            AonLog.e(TAG, "register failed", t)
            return -1
        }
    }

    @Synchronized
    fun unregister() {
        if (clientId == -1L) return
        try {
            val b = getService()
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(IFACE)
                data.writeLong(clientId)
                b.transact(TX_UNREGISTER, data, reply, 0)
                reply.readException()
                AonLog.i(TAG, "unregistered clientId=$clientId")
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (t: Throwable) {
            AonLog.w(TAG, "unregister failed (tolerated): $t")
        } finally {
            clientId = -1L
        }
    }

    /** Diagnostic query of AON sensor capabilities. */
    @Synchronized
    fun queryInfo() {
        try {
            val b = getService()
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(IFACE)
                val ok = b.transact(TX_GET_INFO, data, reply, 0)
                reply.readException()
                val count = reply.readInt()
                AonLog.i(TAG, "GetAONSensorInfoList ok=$ok sensors=$count")
                var i = 0
                while (i < count && reply.dataAvail() >= 4) {
                    val elLen = reply.readInt()
                    val a = reply.readInt()
                    val c = reply.readInt()
                    val capCount = reply.readInt()
                    AonLog.i(TAG, "sensor[$i] len=$elLen a=$a b=$c caps=$capCount")
                    var c2 = 0
                    while (c2 < capCount && reply.dataAvail() >= 4) {
                        val capLen = reply.readInt()
                        val f0 = reply.readInt()
                        val f1 = reply.readInt()
                        val modeCount = reply.readInt()
                        AonLog.i(TAG, "  cap[$c2] len=$capLen f0=$f0 f1=$f1 modes=$modeCount")
                        for (m in 0 until modeCount) {
                            if (reply.dataAvail() < 4) break
                            val mLen = reply.readInt()
                            val mv = IntArray(maxOf(0, mLen / 4))
                            for (k in mv.indices) {
                                if (reply.dataAvail() < 4) break
                                mv[k] = reply.readInt()
                            }
                            AonLog.i(TAG, "    mode[$m] " + Arrays.toString(mv))
                        }
                        c2++
                    }
                    i++
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (t: Throwable) {
            AonLog.e(TAG, "queryInfo failed", t)
        }
    }

    companion object {
        private const val TAG = "AONHAL"
        const val IFACE = "vendor.qti.hardware.camera.aon.IAONService/default"
        const val CB_IFACE = "vendor.qti.hardware.camera.aon.IAONServiceCallback"
        private const val TX_GET_INFO = 1
        private const val TX_REGISTER = 2
        private const val TX_UNREGISTER = 3

        /** Write a 0 placeholder; returns its absolute position for [patch]. */
        private fun mark(p: Parcel): Int {
            val pos = p.dataPosition()
            p.writeInt(0)
            return pos
        }

        /** Patch the placeholder at pos with the current end position, then restore. */
        private fun patch(p: Parcel, pos: Int) {
            val cur = p.dataPosition()
            p.setDataPosition(pos)
            p.writeInt(cur)
            p.setDataPosition(cur)
        }
    }
}
