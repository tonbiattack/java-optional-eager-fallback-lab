package jp.tonbiattack.debuglab;

import java.util.Optional;

/**
 * Selects a requested coupon or a repository-provided default.
 *
 * <p>BUG: the fallback expression passed to {@code orElse} is evaluated even
 * when the Optional already contains a value. That causes an unnecessary
 * repository call and can trigger side effects.</p>
 */
public final class CouponSelector {

    private final CouponRepository repository;

    public CouponSelector(CouponRepository repository) {
        this.repository = repository;
    }

    public String selectCoupon(Optional<String> requestedCoupon) {
        return requestedCoupon.orElse(repository.findDefaultCoupon());
    }
}
