package com.sundry.shop;

import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.file.FileKind;
import com.sundry.file.FileObject;
import com.sundry.file.FileObjectMapper;
import com.sundry.shop.dto.UpdateShopRequest;
import com.sundry.storage.ObjectStorage;
import org.springframework.stereotype.Service;

/**
 * 单商户店铺资料。公开只读，只有超级管理员能改。
 */
@Service
public class ShopService {

    private final ShopProfileMapper shopProfileMapper;
    private final FileObjectMapper fileObjectMapper;
    private final ObjectStorage storage;

    public ShopService(ShopProfileMapper shopProfileMapper, FileObjectMapper fileObjectMapper,
                       ObjectStorage storage) {
        this.shopProfileMapper = shopProfileMapper;
        this.fileObjectMapper = fileObjectMapper;
        this.storage = storage;
    }

    public ShopView view() {
        ShopProfile profile = shopProfileMapper.find();
        if (profile == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        String logoUrl = null;
        if (profile.getLogoBucket() != null && profile.getLogoObjectKey() != null) {
            logoUrl = storage.publicUrl(profile.getLogoBucket(), profile.getLogoObjectKey());
        }
        return new ShopView(profile.getShopName(), logoUrl, profile.getLogoFileId(),
                profile.getDescription(), profile.getContact(), profile.getShippingAddress());
    }

    public ShopView update(UpdateShopRequest request) {
        if (request.logoFileId() != null) {
            FileObject logo = fileObjectMapper.findById(request.logoFileId());
            if (logo == null || logo.getKind() != FileKind.IMAGE) {
                throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
            }
        }
        shopProfileMapper.update(
                request.shopName().trim(),
                normalize(request.description()),
                normalize(request.contact()),
                normalize(request.shippingAddress()),
                request.logoFileId());
        return view();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
