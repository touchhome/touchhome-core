package org.touchhome.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.touchhome.app.setting.console.lines.log.ConsoleLogLevelSetting;
import org.touchhome.bundle.api.EntityContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(SpringRunner.class)
@SpringBootTest
public class StartupTest {

    @Autowired
    private EntityContext entityContext;

    @Test
    public void testStartup() {
        assertNotNull(entityContext);
        assertEquals(ConsoleLogLevelSetting.LogLevel.Info, entityContext.setting().getValue(ConsoleLogLevelSetting.class));
    }
}
