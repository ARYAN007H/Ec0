// Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
// GNOME Shell 45+ Quick Settings toggle delegating strictly via argv-only Gio.Subprocess.

import GObject from 'gi://GObject';
import Gio from 'gi://Gio';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import * as QuickSettings from 'resource:///org/gnome/shell/ui/quickSettings.js';
import { Extension } from 'resource:///org/gnome/shell/extensions/extension.js';

function runEc0Cli(args) {
    try {
        Gio.Subprocess.new(['ec0', ...args], Gio.SubprocessFlags.NONE);
    } catch (e) {
        logError(e, 'ec0-continuity CLI spawn failed');
    }
}

const Ec0QuickMenuToggle = GObject.registerClass(
class Ec0QuickMenuToggle extends QuickSettings.QuickMenuToggle {
    _init() {
        super._init({
            title: 'ec0 Continuity',
            subtitle: 'Paired Phone',
            iconName: 'phone-symbolic',
            toggleMode: true,
        });

        this.menu.setHeader('phone-symbolic', 'ec0 Continuity', 'Encrypted Ecosystem');

        const items = [
            ['Auto-Type 2FA OTP', ['--auto-type-otp']],
            ['Toggle Focus (DND)', ['--focus', 'dnd']],
            ['Clear Focus', ['--focus', 'off']],
            ['Mount ~/Phone (WebDAV)', ['--mount-vfs']],
        ];

        for (const [label, argv] of items) {
            const item = new PopupMenu.PopupMenuItem(label);
            item.connect('activate', () => runEc0Cli(argv));
            this.menu.addMenuItem(item);
        }

        this.connect('clicked', () => {
            runEc0Cli(['--focus', this.checked ? 'dnd' : 'off']);
        });
    }
});

const Ec0Indicator = GObject.registerClass(
class Ec0Indicator extends QuickSettings.SystemIndicator {
    _init() {
        super._init();
        this._indicator = this._addIndicator();
        this._indicator.iconName = 'phone-symbolic';
        this.quickSettingsItems.push(new Ec0QuickMenuToggle());
    }
});

export default class Ec0ContinuityExtension extends Extension {
    enable() {
        this._indicator = new Ec0Indicator();
        Main.panel.statusArea.quickSettings.addExternalIndicator(this._indicator);
    }

    disable() {
        if (this._indicator) {
            this._indicator.quickSettingsItems.forEach(item => item.destroy());
            this._indicator.destroy();
            this._indicator = null;
        }
    }
}
