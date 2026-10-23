package com.sundry.address;

public record AddressView(Long id, String receiverName, String receiverPhone, String province,
                          String city, String district, String detail, boolean isDefault) {

    public static AddressView from(UserAddress address) {
        return new AddressView(address.getId(), address.getReceiverName(), address.getReceiverPhone(),
                address.getProvince(), address.getCity(), address.getDistrict(), address.getDetail(),
                address.getIsDefault() != null && address.getIsDefault() == 1);
    }
}
