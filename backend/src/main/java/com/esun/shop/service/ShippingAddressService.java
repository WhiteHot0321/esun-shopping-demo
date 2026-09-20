package com.esun.shop.service;

import com.esun.shop.exception.BusinessException;
import com.esun.shop.repository.ShippingAddressRepository;
import com.esun.shop.repository.ShippingAddressRepository.ShippingAddress;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ShippingAddressService {
    private final ShippingAddressRepository repository;

    public ShippingAddressService(ShippingAddressRepository repository) {
        this.repository = repository;
    }

    public List<ShippingAddress> list(String email) {
        return repository.findAllByMemberEmail(email);
    }

    @Transactional
    public ShippingAddress create(String email, AddressInput input) {
        boolean makeDefault = Boolean.TRUE.equals(input.isDefault()) || !repository.hasAny(email);
        if (makeDefault) repository.clearDefault(email);
        long id = repository.insert(email, input.label(), input.receiverName(), input.phone(),
                blankToNull(input.postalCode()), input.address(), makeDefault);
        if (id == 0) throw new BusinessException("會員不存在", HttpStatus.NOT_FOUND);
        return requireOwned(id, email);
    }

    @Transactional
    public ShippingAddress update(long id, String email, AddressInput input) {
        requireOwned(id, email);
        repository.update(id, email, input.label(), input.receiverName(), input.phone(),
                blankToNull(input.postalCode()), input.address());
        if (Boolean.TRUE.equals(input.isDefault())) setDefault(id, email);
        return requireOwned(id, email);
    }

    @Transactional
    public ShippingAddress setDefault(long id, String email) {
        requireOwned(id, email);
        repository.clearDefault(email);
        if (repository.markDefault(id, email) != 1) {
            throw new BusinessException("收件地址不存在", HttpStatus.NOT_FOUND);
        }
        return requireOwned(id, email);
    }

    @Transactional
    public void delete(long id, String email) {
        ShippingAddress address = requireOwned(id, email);
        if (repository.isUsedByOrder(id)) {
            throw new BusinessException("此地址已被訂單使用，無法刪除", HttpStatus.CONFLICT);
        }
        repository.delete(id, email);
        if (address.isDefault()) {
            Long replacement = repository.firstAddressId(email);
            if (replacement != null) repository.markDefault(replacement, email);
        }
    }

    public ShippingAddress resolveForOrder(Long requestedId, String email) {
        if (requestedId != null) return requireOwned(requestedId, email);
        return repository.findDefaultByMemberEmail(email)
                .orElseThrow(() -> new BusinessException("請先新增或選擇收件地址", HttpStatus.BAD_REQUEST));
    }

    private ShippingAddress requireOwned(long id, String email) {
        return repository.findByIdAndMemberEmail(id, email)
                .orElseThrow(() -> new BusinessException("收件地址不存在", HttpStatus.NOT_FOUND));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record AddressInput(String label, String receiverName, String phone,
            String postalCode, String address, Boolean isDefault) {}
}
