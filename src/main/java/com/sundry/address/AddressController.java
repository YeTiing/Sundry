package com.sundry.address;

import com.sundry.address.dto.AddressRequest;
import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前用户的收货地址簿。
 */
@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public List<AddressView> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return addressService.list(requireUser(user));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressView create(@Valid @RequestBody AddressRequest request,
                              @AuthenticationPrincipal AuthenticatedUser user) {
        return addressService.create(requireUser(user), request);
    }

    @PutMapping("/{id}")
    public AddressView update(@PathVariable Long id,
                              @Valid @RequestBody AddressRequest request,
                              @AuthenticationPrincipal AuthenticatedUser user) {
        return addressService.update(requireUser(user), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id,
                       @AuthenticationPrincipal AuthenticatedUser user) {
        addressService.delete(requireUser(user), id);
    }

    @PostMapping("/{id}/default")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefault(@PathVariable Long id,
                           @AuthenticationPrincipal AuthenticatedUser user) {
        addressService.setDefault(requireUser(user), id);
    }

    private Long requireUser(AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user.id();
    }
}
