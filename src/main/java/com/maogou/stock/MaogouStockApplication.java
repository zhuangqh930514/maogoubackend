package com.maogou.stock;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("com.maogou.stock.mapper")
@ConfigurationPropertiesScan
@SpringBootApplication
public class MaogouStockApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaogouStockApplication.class, args);
    }
}
