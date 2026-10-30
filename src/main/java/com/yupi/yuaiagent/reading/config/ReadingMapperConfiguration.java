package com.yupi.yuaiagent.reading.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.yupi.yuaiagent.reading.persistence.mapper.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import com.yupi.yuaiagent.reading.persistence.ReadingStartupRecovery;

import java.util.List;
import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
@MapperScan(basePackageClasses = ReadingSessionMapper.class, sqlSessionTemplateRef = "readingSqlSessionTemplate")
public class ReadingMapperConfiguration {
    /**
     * Also used by isolated database tests; never creates another data source.
     */
    public static SqlSessionFactory factory(DataSource dataSource) {
        try {
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            var bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(dataSource);
            bean.setConfiguration(configuration);
            bean.setMapperLocations(new ClassPathResource("mybatis/reading/ReadingVectorMapper.xml"));
            var factory = bean.getObject();
            for (Class<?> mapper : List.of(ReadingSessionMapper.class, ReadingRunMapper.class, ReadingKbMapper.class,
                    ReadingHistorySummaryMapper.class, ReadingBookDocumentMapper.class,
                    ReadingBookChunkMapper.class, ReadingVectorMapper.class, ReadingRecoveryMapper.class)) {
                if (!factory.getConfiguration().hasMapper(mapper)) factory.getConfiguration().addMapper(mapper);
            }
            return factory;
        } catch (Exception e) {
            throw new IllegalStateException("阅读器Mapper初始化失败", e);
        }
    }

    @Bean(initMethod = "recover")
    ReadingStartupRecovery readingStartupRecovery(
            ReadingRecoveryMapper mapper, @Qualifier("readingTransactions") TransactionTemplate transactions) {
        return new ReadingStartupRecovery(mapper, transactions);
    }

    @Bean
    SqlSessionFactory readingSqlSessionFactory(@Qualifier("readingJdbc") JdbcTemplate readingJdbc) {
        return factory(readingJdbc.getDataSource());
    }

    @Bean
    SqlSessionTemplate readingSqlSessionTemplate(@Qualifier("readingSqlSessionFactory") SqlSessionFactory readingSqlSessionFactory) {
        return new SqlSessionTemplate(readingSqlSessionFactory);
    }
}
