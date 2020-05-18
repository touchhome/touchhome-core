package org.touchhome.bundle.api.hardware.other;

import io.swagger.annotations.ApiParam;
import org.touchhome.bundle.api.hardware.api.HardwareQueries;
import org.touchhome.bundle.api.hardware.api.HardwareQuery;
import org.touchhome.bundle.api.hardware.api.HardwareRepositoryAnnotation;

@HardwareRepositoryAnnotation
public interface PostgreSQLHardwareRepository {

    @HardwareQuery(value = "$PM install -y postgresql", printOutput = true, maxSecondsTimeout = 300)
    void installPostgreSQL();

    @HardwareQuery("psql --version")
    String getPostgreSQLVersion();

    @HardwareQuery(value = {"/sbin/service", "postgresql", "status"})
    boolean isPostgreSQLRunning();

    @HardwareQuery("-u postgres psql -c \"ALTER USER postgres PASSWORD ':pwd';\"")
    void changePostgresPassword(@ApiParam("pwd") String pwd);

    @HardwareQuery(value = "service postgresql start", printOutput = true)
    void startPostgreSQLService();

}









