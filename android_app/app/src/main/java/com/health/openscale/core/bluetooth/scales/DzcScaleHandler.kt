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
import com.health.openscale.core.bluetooth.DeviceCapability
import com.health.openscale.core.bluetooth.DeviceSupport
import com.health.openscale.core.bluetooth.LinkMode
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

        setNotifyOn(
            SERVICE_UUID,
            NOTIFY_CHAR_UUID
        )

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
                "Ignoring DZC packet with unexpected length: " +
                    "${data.size}, data=${data.toHexPreview(24)}"
            )
            return
        }

        logD(
            "DZC measurement packet: ${data.toHexPreview(24)}"
        )

        val header = data[0].toInt() and 0xFF

        if (header != PACKET_HEADER) {
            logD(
                "Ignoring DZC packet with invalid header: " +
                    "0x%02X".format(header)
            )
            return
        }

        /*
         * Verify XOR checksum across bytes 0 through 9.
         *
         * Reference:
         * CF DC 14 52 21 DC 14 00 01 A0 = 1D
         */
        var checksum = 0

        for (i in 0 until 10) {
            checksum = checksum xor
                (data[i].toInt() and 0xFF)
        }

        val packetChecksum = data[10].toInt() and 0xFF

        if (checksum != packetChecksum) {
            logE(
                "DZC checksum mismatch: calculated=0x%02X expected=0x%02X"
                    .format(checksum, packetChecksum)
            )
            return
        }

        /*
         * Bytes 1..2:
         * Auxiliary sensor value, little-endian.
         */
        val auxiliaryRaw =
            ((data[2].toInt() and 0xFF) shl 8) or
                (data[1].toInt() and 0xFF)

        /*
         * Bytes 3..4:
         * Weight in 0.01 kg, little-endian.
         *
         * Example:
         * 52 21 -> 0x2152 -> 8530 -> 85.30 kg
         */
        val rawWeight =
            ((data[4].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)

        val weightKg = rawWeight / 100.0f

        /*
         * Bytes 5..6:
         * Bioimpedance in 0.1 ohm, little-endian.
         *
         * Example:
         * DC 14 -> 0x14DC -> 5340 -> 534.0 ohm
         */
        val rawImpedance =
            ((data[6].toInt() and 0xFF) shl 8) or
                (data[5].toInt() and 0xFF)

        val impedanceOhm = rawImpedance / 10.0f

        /*
         * Byte 7:
         * Scale profile/user slot.
         */
        val profileId = data[7].toInt() and 0xFF

        /*
         * Byte 8:
         * 0x00 = dynamic/live measurement
         * 0x01 = final stabilized measurement
         */
        val status = data[8].toInt() and 0xFF

        /*
         * Byte 9:
         * Unit/mode flags.
         * Observed value: 0xA0
         */
        val modeFlags = data[9].toInt() and 0xFF

        logD(
            "DZC decoded: " +
                "aux=$auxiliaryRaw, " +
                "weight=$weightKg kg, " +
                "impedance=$impedanceOhm ohm, " +
                "profile=$profileId, " +
                "status=0x%02X, ".format(status) +
                "mode=0x%02X".format(modeFlags)
        )

        /*
         * The scale streams dynamic measurements while the user is
         * standing on the scale. Only persist the final stabilized
         * measurement.
         */
        if (status != STATUS_STABILIZED) {
            if (status == STATUS_DYNAMIC) {
                logD(
                    "DZC live measurement: " +
                        "$weightKg kg"
                )
            } else {
                logD(
                    "Ignoring DZC packet with unknown status: " +
                        "0x%02X".format(status)
                )
            }

            return
        }

        /*
         * Build the measurement using the current openScale
         * MeasurementType API.
         */
        val measurement = ScaleMeasurement().apply {
            userId = user.id

            this[MeasurementType.WEIGHT] =
                Kg(weightKg)

            if (impedanceOhm > 0f) {
                this[MeasurementType.IMPEDANCE] =
                    Ohm(impedanceOhm)
            }
        }

        logI(
            "DZC stabilized measurement received: " +
                "$weightKg kg, $impedanceOhm ohm. " +
                "Publishing to app."
        )

        publish(measurement)

        requestDisconnect()
    }
}