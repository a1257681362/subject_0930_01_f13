package com.evops.coldchain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.coldchain.entity.TraceNode;
import org.apache.ibatis.annotations.Mapper;

/** 药品批次追溯图节点 Mapper（V13） */
@Mapper
public interface TraceNodeMapper extends BaseMapper<TraceNode> {
}
