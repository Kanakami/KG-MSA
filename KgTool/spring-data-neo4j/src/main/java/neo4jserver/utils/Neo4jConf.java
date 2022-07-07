package neo4jserver.utils;

import org.neo4j.driver.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * neo4j配置
 */
@Configuration
public class Neo4jConf {

    @Value("${spring.data.neo4j.uri}")
    private String url;

    @Value("${spring.data.neo4j.username}")
    private String username;

    @Value("${spring.data.neo4j.password}")
    private String password;

//    @Bean
//    public Driver initDriver() {
//        Driver driver;
//        try {
//            SessionConfig.builder().withDefaultAccessMode(AccessMode.WRITE)
//            driver = GraphDatabase.driver(url, AuthTokens.basic(username, password)).session(SessionConfig.builder().withDefaultAccessMode(AccessMode.WRITE).build());
//;        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//        return driver;
//    }
}
