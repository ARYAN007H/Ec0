// Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
import QtQuick
import QtQuick.Layouts
import org.kde.plasma.plasmoid
import org.kde.plasma.components as PlasmaComponents
import org.kde.plasma.plasma5support as Plasma5Support

PlasmoidItem {
    id: root
    preferredRepresentation: compactRepresentation

    Plasma5Support.DataSource {
        id: executable
        engine: "executable"
        connectedSources: []
        onNewData: function(sourceName, data) {
            disconnectSource(sourceName)
        }
        function execSafe(cmd) {
            connectSource(cmd)
        }
    }

    fullRepresentation: ColumnLayout {
        Layout.minimumWidth: 260
        Layout.minimumHeight: 200
        spacing: 8

        PlasmaComponents.Label {
            text: "ec0 Continuity"
            font.bold: true
        }
        PlasmaComponents.Button {
            Layout.fillWidth: true
            text: "Auto-Type Latest 2FA OTP"
            onClicked: executable.execSafe("ec0 --auto-type-otp")
        }
        PlasmaComponents.Button {
            Layout.fillWidth: true
            text: "Enable Focus (DND)"
            onClicked: executable.execSafe("ec0 --focus dnd")
        }
        PlasmaComponents.Button {
            Layout.fillWidth: true
            text: "Clear Focus"
            onClicked: executable.execSafe("ec0 --focus off")
        }
        PlasmaComponents.Button {
            Layout.fillWidth: true
            text: "Mount ~/Phone Storage"
            onClicked: executable.execSafe("ec0 --mount-vfs")
        }
    }
}
