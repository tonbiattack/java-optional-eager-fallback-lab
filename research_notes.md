# 調査メモ：`Optional.orElse()` と `orElseGet()` の評価タイミング

## 既存題材との重複調査

`tonbiattack/qiita` の既存記事・記事パスを検索し、Optional関連の既存題材を確認した。

| 既存題材 | 内容 | 今回との差分 |
|---|---|---|
| `JavaのOptional.getでクーポンなし注文が落ちる原因.md` | 空の `Optional` に対する `get()` が `NoSuchElementException` になる問題。業務上の既定値として `orElse(0)` を使う話を含む。 | 今回は空のOptionalによる例外ではない。**値が存在していても `orElse` の引数式が評価され、副作用・不要なDB呼び出し・カウンタ増加が起きる**という評価タイミングの問題を扱う。 |
| `Javaで良く起きる例外クラス.md` | 例外クラスの一般的なメモ。 | 特定のOptional契約を失敗テストで再現する記事ではない。 |
| Javaの既存リモートラボ | `try-with-resources`、`ThreadLocal`、`Stream.toList`、Unicodeなど。 | Optionalの `orElse` / `orElseGet` 評価差とは発火条件・原因・修正中心が異なる。 |

今回固有の契約は、`orElse(T other)` では引数として渡す式が呼び出し側で先に評価される一方、`orElseGet(Supplier<? extends T>)` では値が存在しない場合の供給関数として使うという、**デフォルト値の生成タイミングと副作用境界**である。

## 公式資料の根拠

| 資料 | 確認した契約 | 記事での利用 |
|---|---|---|
| [Optional — Java SE 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Optional.html) | `orElse(T other)` は値があればその値を返し、なければ `other` を返す。`orElseGet(Supplier)` は値があればその値を返し、なければ供給関数の結果を返す。 | 引数式とSupplierの評価タイミングの説明。 |
| [Supplier — Java SE 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/function/Supplier.html) | `Supplier` は結果を供給する関数型インターフェースで、メソッドは `get()`。 | 遅延させたい処理をSupplierへ包む説明。 |
| [Java Language Specification 15.12.4](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.12.4) | メソッド呼び出しの引数式はメソッド本体実行前に評価される。 | `orElse(expensiveOrSideEffectingFallback())` が先に評価される根拠。 |

## 題材設計

注文にクーポンが設定されている場合、クーポン名を返す。未設定の場合だけ `CouponRepository.findDefaultCoupon()` を呼び出すつもりだったが、`orElse(repository.findDefaultCoupon())` ではクーポンが存在していてもリポジトリ呼び出しが実行される。固定FakeRepositoryの呼び出し回数で観測する。

| 仮説 | 予測 | 最小実験 | 判定 |
|---|---|---|---|
| A. `Optional` が値を返してもFallbackは実行されない | presentケースのリポジトリ呼び出しは0回 | present値 + FakeRepositoryの呼び出し回数を確認 | 棄却予定 |
| B. `orElse` の引数式が先に評価される | presentケースでも呼び出し回数が1回 | `orElse(fake.findDefaultCoupon())` を実行 | 採用予定 |
| C. `orElseGet` も同じ挙動である | presentケースでもSupplierが1回呼ばれる | `orElseGet(fake::findDefaultCoupon)` を実行 | 棄却予定 |

## 前提バージョン

* JDK: 21
* Maven: 3.8以上
* JUnit Jupiter: 5.11.4
* 外部サービス・現在時刻・乱数・既定ロケールに依存しない。
