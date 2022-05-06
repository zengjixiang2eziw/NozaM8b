package com.medusa.gruul.logistics.model.dto.manager;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.medusa.gruul.common.dto.AreaDto;
import com.medusa.gruul.logistics.api.entity.LogisticsIncludePostage;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author lcy
 */
@Data
@ApiModel("新增或修改指定包邮运送方式信息DTO")
public class LogisticsIncludePostageDto {

    @ApiModelProperty("唯一 id, 新增不传,修改传原值 ")
    private Long id;

    @ApiModelProperty("模板 id,新增运费模板的时候可不传")
    private Long logisticsId;

    @ApiModelProperty("包邮条件类型: 0= 按件数包邮,1=按重量包邮,2=按金额包邮,3=件数+金额 4=重量+金额 包邮")
    private Integer type;

    @ApiModelProperty("包邮地区 格式: {\"provinceid\": [\"cityId\",\"cityId2\"]}")
    private List<AreaDto> region;

    @ApiModelProperty("包邮 件数")
    private Integer pieceNum;

    @ApiModelProperty("包邮重量 单位: 千克(kg) ")
    private BigDecimal weight;

    @ApiModelProperty("包邮金额 单位/元")
    private BigDecimal amountNum;

    public LogisticsIncludePostage coverBean() {
        LogisticsIncludePostage logisticsIncludePostage = new LogisticsIncludePostage();
        BeanUtil.copyProperties(this, logisticsIncludePostage);
        logisticsIncludePostage.setRegion(JSON.toJSONString(this.getRegion()));
        return logisticsIncludePostage;
    }

}
