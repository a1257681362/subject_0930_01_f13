package com.evops.coldchain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.coldchain.entity.TraceConcurrentLog;
import org.apache.ibatis.annotations.Mapper;

/** 同一操作并发仲裁审计 Mapper（V13，只追加） */
@Mapper
public interface TraceConcurrentLogMapper extends BaseMapper<TraceConcurrentLog> {
}
