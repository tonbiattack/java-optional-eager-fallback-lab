# 調査記録：`orElse` に渡したFallbackがpresent値でも実行される理由

## 症状

クーポンが `SPRING10` として存在する場合、戻り値は正しく `SPRING10` になる。しかし、デフォルトクーポン取得メソッド `findDefaultCoupon()` が1回呼ばれる。業務上は、present値がある場合にデフォルト取得を行わない契約である。

## 不具合状態の観測

不具合状態で `mvn test` を実行した結果を [`../evidence/01-broken-test-output.txt`](../evidence/01-broken-test-output.txt) に保存した。

```text
findDefaultCoupon called: count=1, value=DEFAULT
expected: <0> but was: <1>
```

戻り値の値だけを見ると `SPRING10` で正しい。そこでテストでは、Fake Repositoryの呼び出し回数を別の観測値として固定した。

| 観測項目 | 不具合状態 | 期待 |
|---|---:|---:|
| present値の戻り値 | `SPRING10` | `SPRING10` |
| `findDefaultCoupon()` の呼び出し回数 | 1 | 0 |
| empty値の戻り値 | `DEFAULT` | `DEFAULT` |
| empty値での呼び出し回数 | 1 | 1 |

## 競合仮説の比較

| 仮説 | 予測 | 最小実験 | 結果 | 判定 |
|---|---|---|---|---|
| Optionalの値が失われている | present値でも戻り値が`DEFAULT`になる | present値と返却値を検証する | `SPRING10` が返る | 棄却 |
| Repositoryのカウンタが誤って増える | 明示的にRepositoryを呼ばなくても回数が増える | Fake Repositoryの直接テストとSelector経由を比較する | Selector経由だけで1回増える | 棄却 |
| `orElse` の引数式がメソッド呼び出し前に評価される | present値でも `findDefaultCoupon()` が呼ばれる | `orElse(repository.findDefaultCoupon())` を実行する | present値で呼び出し1回 | 採用 |
| `orElseGet` もSupplierを常に呼ぶ | present値でSupplier呼び出し1回 | `orElseGet` とカウンタを実行する | 呼び出し0回 | 棄却 |

## 原因

Javaのメソッド呼び出しでは、メソッド本体へ入る前に引数式が評価される。[3] そのため、次のコードは `Optional` が値を持っているかどうかに関係なく `findDefaultCoupon()` を呼ぶ。

```java
requestedCoupon.orElse(repository.findDefaultCoupon());
```

`orElse` は評価済みの `T other` を受け取る。一方、`orElseGet` は `Supplier<? extends T>` を受け取り、Optionalに値がない場合に供給関数の結果を使用する。[1] Supplierは `get()` を持つ関数型インターフェースである。[2]

## 最小修正

デフォルト取得をメソッド参照としてSupplierへ渡す。

```java
return requestedCoupon.orElseGet(repository::findDefaultCoupon);
```

修正後は、present値のケースでカウンタが0、empty値のケースで1になる。完全な成功出力は [`../evidence/02-fixed-test-output.txt`](../evidence/02-fixed-test-output.txt) に保存した。

## 参考資料

[1]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Optional.html "Optional — Java SE 21"
[2]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/function/Supplier.html "Supplier — Java SE 21"
[3]: https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.12.4 "Java Language Specification 21, Run-Time Evaluation of Method Invocation"
