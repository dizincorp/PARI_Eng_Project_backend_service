package pl.pollub.pari.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.pollub.pari.common.domain.PlatformSetting;

public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, String> {
}
