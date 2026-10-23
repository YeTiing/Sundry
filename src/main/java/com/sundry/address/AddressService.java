package com.sundry.address;

import com.sundry.address.dto.AddressRequest;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AddressService {

    private static final int MAX_ADDRESSES = 20;

    private final UserAddressMapper addressMapper;

    public AddressService(UserAddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    public List<AddressView> list(Long userId) {
        return addressMapper.findByUser(userId).stream().map(AddressView::from).toList();
    }

    @Transactional
    public AddressView create(Long userId, AddressRequest request) {
        long count = addressMapper.countByUser(userId);
        if (count >= MAX_ADDRESSES) {
            throw new BusinessException(ErrorCode.ADDRESS_LIMIT_REACHED);
        }
        boolean makeDefault = Boolean.TRUE.equals(request.isDefault()) || count == 0;
        if (makeDefault) {
            addressMapper.clearDefault(userId);
        }

        UserAddress address = new UserAddress();
        address.setUserId(userId);
        apply(address, request);
        address.setIsDefault(makeDefault ? 1 : 0);
        addressMapper.insert(address);
        return AddressView.from(require(userId, address.getId()));
    }

    @Transactional
    public AddressView update(Long userId, Long id, AddressRequest request) {
        UserAddress address = require(userId, id);
        boolean makeDefault = Boolean.TRUE.equals(request.isDefault());
        if (makeDefault) {
            addressMapper.clearDefault(userId);
        }
        apply(address, request);
        address.setIsDefault(makeDefault ? 1
                : (request.isDefault() == null ? address.getIsDefault() : 0));
        addressMapper.update(address);
        return AddressView.from(require(userId, id));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        UserAddress address = require(userId, id);
        addressMapper.deleteByIdAndUser(id, userId);
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            List<UserAddress> remaining = addressMapper.findByUser(userId);
            if (!remaining.isEmpty()) {
                addressMapper.setDefault(remaining.get(0).getId(), userId);
            }
        }
    }

    @Transactional
    public void setDefault(Long userId, Long id) {
        require(userId, id);
        addressMapper.clearDefault(userId);
        addressMapper.setDefault(id, userId);
    }

    private UserAddress require(Long userId, Long id) {
        UserAddress address = addressMapper.findByIdAndUser(id, userId);
        if (address == null) {
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND);
        }
        return address;
    }

    private void apply(UserAddress address, AddressRequest request) {
        address.setReceiverName(request.receiverName().trim());
        address.setReceiverPhone(request.receiverPhone().trim());
        address.setProvince(request.province().trim());
        address.setCity(request.city().trim());
        address.setDistrict(request.district().trim());
        address.setDetail(request.detail().trim());
    }
}
