package com.project.person.admin.service;

import com.project.person.admin.common.BizException;
import com.project.person.admin.mapper.AssetMapper;
import com.project.person.admin.model.Asset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 资产联动校验助手：收支/投资只能关联「现金」「银行卡」类别的资产
 */
@Component
public class AssetSupport {

    private static final Set<String> CASH_CATEGORIES = new HashSet<>(Arrays.asList("现金", "银行卡"));

    @Autowired
    private AssetMapper assetMapper;

    /** 校验并返回可用于联动的资产（必须存在且类别为现金/银行卡） */
    public Asset requireCashAsset(Long userId, Long assetId) {
        if (assetId == null) {
            throw BizException.badRequest("请选择关联的资产");
        }
        Asset asset = assetMapper.findById(assetId, userId);
        if (asset == null) {
            throw BizException.badRequest("资产不存在或不属于当前用户");
        }
        if (!CASH_CATEGORIES.contains(asset.getCategory())) {
            throw BizException.badRequest("只能选择「现金」或「银行卡」类别的资产");
        }
        return asset;
    }

    /** 支出/买入前检查余额是否充足 */
    public void checkBalance(Asset asset, BigDecimal need) {
        if (asset.getCurrentValue().compareTo(need) < 0) {
            throw BizException.badRequest("资产「" + asset.getName() + "」余额不足（当前 ¥"
                    + asset.getCurrentValue() + "，需要 ¥" + need + "）");
        }
    }
}
