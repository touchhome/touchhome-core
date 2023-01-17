package org.touchhome.app.setting.sidebar;

import org.touchhome.bundle.api.setting.SettingPluginBoolean;

public class SidebarFlatMenuSetting implements SettingPluginBoolean {

    @Override
    public boolean defaultValue() {
        return true;
    }

    @Override
    public int order() {
        return 1;
    }
}
