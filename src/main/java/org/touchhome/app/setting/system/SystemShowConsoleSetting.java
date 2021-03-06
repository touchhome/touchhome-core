package org.touchhome.app.setting.system;

import org.touchhome.app.setting.CoreSettingPlugin;
import org.touchhome.bundle.api.setting.SettingPluginBoolean;

public class SystemShowConsoleSetting implements CoreSettingPlugin<Boolean>, SettingPluginBoolean {

    @Override
    public GroupKey getGroupKey() {
        return GroupKey.system;
    }

    @Override
    public boolean defaultValue() {
        return true;
    }

    @Override
    public int order() {
        return 300;
    }
}
