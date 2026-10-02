package com.ticket.defender_core;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("DB 커넥션 없이 가벼운 순수 단위 테스트만 수행하기 위해 전체 컨텍스트 로드를 비활성화합니다.")
class DefenderCoreApplicationTests {
    @Test
    void contextLoads() {
    }
}
