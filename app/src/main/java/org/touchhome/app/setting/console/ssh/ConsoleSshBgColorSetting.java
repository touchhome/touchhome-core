package org.touchhome.app.setting.console.ssh;

import org.touchhome.bundle.api.BundleConsoleSettingPlugin;

public class ConsoleSshBgColorSetting implements BundleConsoleSettingPlugin<String> {

    @Override
    public String getDefaultValue() {
        return "#363636";
    }

    @Override
    public SettingType getSettingType() {
        return SettingType.ColorPicker;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String[] pages() {
        return new String[]{"ssh"};
    }
}