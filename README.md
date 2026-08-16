# Optional.orElse の不要なFallback評価を再現する最小ラボ

このプロジェクトは、クーポンが既に存在する場合でも `Optional.orElse(...)` の引数式が評価され、不要なデフォルトクーポン取得が実行される挙動を再現する Java 21 のデバッグ教材である。

返却されるクーポン値は正しい。しかし、デフォルト取得がデータベース参照、外部API呼び出し、監査ログ、メトリクス増加などの副作用を持つ場合、値が正しいだけでは不具合を検出できない。

## 前提

| 項目 | 固定値 |
|---|---|
| JDK | 21 |
| Maven | 3.8 以上 |
| テスト | JUnit Jupiter 5.11.4 |

外部サービス、現在時刻、乱数、システム既定ロケールには依存しない。`CountingCouponRepository` がデフォルト取得回数を記録する。

## 不具合状態の再現

```bash
mvn test
```

`CouponSelectorTest#presentCoupon_doesNotLookupDefaultCoupon` が、デフォルト取得回数 `1` を観測して失敗する。期待するのは、present値があるときの取得回数 `0` である。

不具合状態では、次のような観測出力になる。

```text
findDefaultCoupon called: count=1, value=DEFAULT
expected: <0> but was: <1>
```

## 修正後の検証

現在の修正済み状態では、次のコマンドで全テストを実行する。

```bash
mvn clean test
```

3テストすべてが成功する。present値ではデフォルト取得が0回、empty値では1回になる。

## 原因と修正の方向

不具合状態は次のコードである。

```java
return requestedCoupon.orElse(repository.findDefaultCoupon());
```

`orElse` は値を直接受け取る。デフォルト取得の呼び出しは、`orElse` が返す値を決める前、つまりメソッド呼び出しの引数評価時点で実行される。

修正後はデフォルト取得をSupplierへ渡す。

```java
return requestedCoupon.orElseGet(repository::findDefaultCoupon);
```

`orElseGet` は値が存在しない場合にSupplierの結果を使用する。Supplierの遅延評価に依存する処理には、データ取得や副作用を含めない、または `orElseGet` で必要時だけ実行するという契約を明示する。

## 構成

```text
src/main/java/jp/tonbiattack/debuglab/CouponRepository.java
src/main/java/jp/tonbiattack/debuglab/CouponSelector.java
src/test/java/jp/tonbiattack/debuglab/CouponSelectorTest.java
docs/investigation.md
evidence/01-broken-test-output.txt
evidence/02-fixed-test-output.txt
research_notes.md
```

## Git履歴

不具合状態と失敗テストは最初のコミット、最小修正と回帰確認は次のコミットへ分離する。
