package jp.tonbiattack.debuglab;

import java.util.Optional;

/**
 * Selects a requested coupon or a repository-provided default.
 *
 * <p>The default lookup is supplied as a method reference so it is evaluated
 * only when the requested coupon is absent.</p>
 */
public final class CouponSelector {

    private final CouponRepository repository;

    public CouponSelector(CouponRepository repository) {
        this.repository = repository;
    }

    public String selectCoupon(Optional<String> requestedCoupon) {
        return requestedCoupon.orElseGet(repository::findDefaultCoupon);
    }
}
