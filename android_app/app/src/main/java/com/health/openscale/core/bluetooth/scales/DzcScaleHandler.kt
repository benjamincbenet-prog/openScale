/*
 * openScale
 *
 * DZC-D18E3 Smart Scale BLE Handler
 *
 * Protocol:
 * - Manufacturer: Bear Electric Appliance (Xiong)
 * - BLE name prefix: DZC
 * - Service: 0xFFF0
 * - Command characteristic: 0xFFF1 (WRITE)
 * - Data characteristic: 0xFFF4 (NOTIFY)
 *
 * Notification packet (11 bytes):
 *
 * [0]     Header       uint8       0xCF
 * [1..2]  Auxiliary    uint16 LE
 * [3..4]  Weight       uint16 LE   / 100.0 kg
 * [5..6]  Impedance    uint16 LE   / 10.0 ohm
 * [7]     Profile ID   uint8
 * [8]     Status       0x00 live, 0x01 stabilized
 * [9]     Mode flags   uint8
 * [10]    Checksum     XOR bytes 0..9
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.UUID

class DzcScaleHandler : ScaleDeviceHandler() {

    companion object {
        private const val PACKET_LENGTH = 11
        private const val PACKET_HEADER = 0xCF

        private const val STATUS_DYNAMIC = 0x00
        private const val STATUS_STABILIZED = 0x01
    }

    private val SERVICE_UUID = uuid16(0xFFF0)
    private val NOTIFY_CHAR_UUID = uuid16(0xFFF4)

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase()

        if (!name.startsWith("DZC")) {
            return null
        }

        return DeviceSupport(
            displayName = "DZC Smart Scale",
            capabilities = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION
            ),
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION
            ),
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        logI("Connected to DZC scale. Enabling measurement notifications.")

        setNotifyOn(SERVICE_UUID, NOTIFY_CHAR_UUID)

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(
        characteristic: UUID,
        data: ByteArray,
        user: ScaleUser
    ) {
        if (characteristic != NOTIFY_CHAR_UUID) {
            return
        }

        if (data.size != PACKET_LENGTH) {
            logD(
                "Ignoring DZC packet with unexpected length: ${data.size}"
            )
            return
        }

        val header = data[0].toInt() and 0xFF

        if (header != PACKET_HEADER) {
            logD("Ignoring DZC packet with invalid header")
            return
        }

        var checksum = 0

        for (i in 0 until 10) {
            checksum = checksum xor
                (data[i].toInt() and 0xFF)
        }

        val packetChecksum = data[10].toInt() and 0xFF

        if (checksum != packetChecksum) {
            logE("DZC checksum mismatch")
            return
        }

        val auxiliaryRaw =
            ((data[2].toInt() and 0xFF) shl 8) or
                (data[1].toInt() and 0xFF)

        val rawWeight =
            ((data[4].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)

        val weightKg = rawWeight / 100.0f

        val rawImpedance =
            ((data[6].toInt() and 0xFF) shl 8) or
                (data[5].toInt() and 0xFF)

        val impedanceOhm = rawImpedance / 10.0f

        val profileId = data[7].toInt() and 0xFF
        val status = data[8].toInt() and 0xFF
        val modeFlags = data[9].toInt() and 0xFF

        logD(
            "DZC decoded: aux=$auxiliaryRaw, " +
                "weight=$weightKg kg, " +
                "impedance=$impedanceOhm ohm, " +
                "profile=$profileId, " +
                "status=0x%02X, ".format(status) +
                "mode=0x%02X".format(modeFlags)
        )

        if (status != STATUS_STABILIZED) {
            if (status == STATUS_DYNAMIC) {
                logD("DZC live measurement: $weightKg kg")
            } else {
                logD(
                    "Ignoring DZC packet with unknown status: " +
                        "0x%02X".format(status)
                )
            }

            return
        }

        val measurement = ScaleMeasurement().apply {
            userId = user.id
            this[MeasurementType.WEIGHT] = Kg(weightKg)

            if (impedanceOhm > 0f) {
                this[MeasurementType.IMPEDANCE] = Ohm(impedanceOhm)
            }
        }

        logI(
            "DZC stabilized measurement: " +
                "$weightKg kg, $impedanceOhm ohm"
        )

        publish(measurement)
        requestDisconnect()
    }
}