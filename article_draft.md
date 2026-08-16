# Javaの`Optional.orElse()`で不要なFallback処理が実行される理由：`orElseGet()`との評価タイミングを最小再現から理解する

`Optional` に値が入っているなら、デフォルト値を作る処理は実行されない。多くのコードレビューでは、そのように読みたくなります。しかし、次のコードはその期待を裏切ります。

```java
return requestedCoupon.orElse(repository.findDefaultCoupon());
```

クーポンの戻り値は正しくても、`findDefaultCoupon()` は実行されます。デフォルト取得がデータベース参照、外部API呼び出し、監査ログ、メトリクス増加などを伴う場合、これは単なる性能問題ではなく、不要な副作用を発生させる不具合です。

本稿では Java 21 と JUnit 5 でこの挙動を再現します。結論は、**値を直接渡す `orElse` と、必要時に呼び出すSupplierを渡す `orElseGet` は、同じFallback用途でも評価タイミングが異なる**ということです。

## この記事で扱う問題

対象は、Optionalの値があればそれを使い、なければRepositoryから既定のクーポンを取得するサービスです。業務上の契約は次のとおりです。

| 入力状態 | 返す値 | Repositoryの既定値取得 |
|---|---|---:|
| クーポンが存在する | 指定クーポン | 実行しない |
| クーポンが存在しない | 既定クーポン | 1回だけ実行する |

JDK 21のOptional APIは、`orElse(T other)` を「値があればその値、なければ `other`」と説明し、`orElseGet(Supplier<? extends T>)` を「値があればその値、なければ供給関数の結果」と説明しています。[1] このシグネチャの違いが、処理の実行タイミングを分けます。

## 既存題材との差分

既存のqiita記事には、空の `Optional` に対して `get()` を呼び、`NoSuchElementException` になる題材がありました。その記事は、空値をどういう業務ルールで扱うか、`get()` と既定値の選択を中心に扱っています。

今回の不具合は、空値による例外ではありません。**値が存在していて戻り値も正しいのに、不要なFallback処理が実行される**問題です。今回学ぶ固有の契約は、Optionalの有無判定そのものではなく、引数式の評価とSupplierの遅延評価の境界です。

## 期待していた挙動と実際の挙動

不具合状態の `CouponSelector` は次の実装です。

```java
public String selectCoupon(Optional<String> requestedCoupon) {
    return requestedCoupon.orElse(repository.findDefaultCoupon());
}
```

呼び出し側は、`SPRING10` が存在すればRepositoryへ問い合わせず、そのまま返すと期待します。実際には、値は正しいもののRepositoryの呼び出し回数が増えます。

```text
findDefaultCoupon called: count=1, value=DEFAULT
expected: <0> but was: <1>
```

| 観測項目 | 期待 | 不具合状態 |
|---|---:|---:|
| present値の戻り値 | `SPRING10` | `SPRING10` |
| present値での`findDefaultCoupon()`呼び出し | 0回 | 1回 |
| empty値の戻り値 | `DEFAULT` | `DEFAULT` |
| empty値での`findDefaultCoupon()`呼び出し | 1回 | 1回 |

戻り値だけをアサートすると、この不具合は見逃します。そこでFake Repositoryの呼び出し回数を、値とは別の観測値としてテストします。

## 最小再現プロジェクト

プロジェクトは [`/home/ubuntu/java-optional-eager-fallback-lab`](.) にあります。主要ファイルは次のとおりです。

```text
src/main/java/jp/tonbiattack/debuglab/CouponRepository.java
src/main/java/jp/tonbiattack/debuglab/CouponSelector.java
src/test/java/jp/tonbiattack/debuglab/CouponSelectorTest.java
docs/investigation.md
evidence/01-broken-test-output.txt
evidence/02-fixed-test-output.txt
```

不具合状態はコミット `3f610c2` に保存しています。再現コマンドは次のとおりです。

```bash
git checkout 3f610c2
mvn test
```

利用者視点の失敗テストは次のようになっています。

```java
@Test
void presentCoupon_doesNotLookupDefaultCoupon() {
    CountingCouponRepository repository = new CountingCouponRepository("DEFAULT");
    CouponSelector selector = new CouponSelector(repository);

    String actual = selector.selectCoupon(Optional.of("SPRING10"));

    assertEquals("SPRING10", actual);
    assertEquals(0, repository.findCalls(),
            "クーポンが存在する場合はデフォルト取得を実行すべきではない");
}
```

失敗状態では3テスト中1件が失敗し、`expected: <0> but was: <1>` になります。完全な出力は [`evidence/01-broken-test-output.txt`](evidence/01-broken-test-output.txt) に保存しています。

## 調査：何を観測し、どの仮説を除外したか

「呼び出し回数が増えた」という症状から、値の紛失、Fakeのカウンタ不具合、引数評価の問題を切り分けました。

| 仮説 | 予測 | 最小実験 | 結果 | 判定 |
|---|---|---|---|---|
| Optionalの値が失われている | present値でも`DEFAULT`が返る | present値と戻り値を検証する | `SPRING10`が返る | 棄却 |
| Fake Repositoryのカウンタが誤って増える | Selectorを通さなくても増える | Fakeの直接利用とSelector経由を比較する | Selector経由でだけ増える | 棄却 |
| `orElse`の引数式が先に評価される | present値でもFallbackが呼ばれる | `orElse(repository.findDefaultCoupon())`を実行する | present値で1回呼ばれる | 採用 |
| `orElseGet`もSupplierを常に呼ぶ | present値でSupplierが1回呼ばれる | Supplier内のカウンタを観測する | 0回 | 棄却 |

根本原因は、Javaのメソッド呼び出しの評価規則です。JLSは、メソッド呼び出しの引数式がメソッド本体の実行前に評価されることを定めています。[3] したがって、次の式では `findDefaultCoupon()` が `orElse` の内部判断より前に実行されます。

```java
requestedCoupon.orElse(repository.findDefaultCoupon());
```

`Optional` が値を持っているかどうかは、引数式の評価を止めません。`orElse` に渡されるのは、すでに実行済みのメソッドの戻り値です。

一方、`orElseGet` に渡すのは `Supplier` です。Supplierは結果を供給する関数型インターフェースで、抽象メソッドは `get()` です。[2] `Optional` に値がある場合はSupplierを呼ばず、値がない場合だけ `get()` の結果を使います。[1]

## 修正：なぜ`orElseGet`で直るのか

修正は1行です。

```diff
- return requestedCoupon.orElse(repository.findDefaultCoupon());
+ return requestedCoupon.orElseGet(repository::findDefaultCoupon);
```

修正後のコードでは、Repositoryの呼び出し処理がメソッド参照としてSupplierに包まれます。present値の場合は `orElseGet` がその値を返してSupplierを呼びません。empty値の場合だけSupplierが呼ばれ、既定クーポンが取得されます。

| 書き方 | Fallback引数の形 | present値でFallback処理 | empty値でFallback処理 |
|---|---|---:|---:|
| `orElse(repository.findDefaultCoupon())` | 評価済みの値 | 実行される | 実行される |
| `orElseGet(repository::findDefaultCoupon)` | Supplier | 実行されない | 1回実行される |

ただし、すべての `orElse` を機械的に `orElseGet` へ置き換えるべきではありません。定数や、計算コストと副作用がない単純な式なら `orElse` のほうが読みやすい場合があります。データ取得、状態変更、ログ記録、例外生成など、実行そのものに意味がある処理をFallbackに置くなら、`orElseGet` で必要時の評価を表現します。

## 回帰テスト

修正後も、最初に失敗したpresent値のテストは残しています。empty値の対照ケースと、Optional APIのSupplier評価を直接確認するテストもあります。

| テスト | 固定する契約 |
|---|---|
| `presentCoupon_doesNotLookupDefaultCoupon` | present値では既定値取得を0回にする。 |
| `emptyCoupon_looksUpDefaultCouponOnce` | empty値では既定値を1回取得する。 |
| `orElseGet_doesNotInvokeSupplierWhenValueIsPresent` | present値ではSupplierを呼ばない。 |

`mvn clean test` の結果は、3テスト成功、失敗0、エラー0でした。成功出力は [`evidence/02-fixed-test-output.txt`](evidence/02-fixed-test-output.txt) に保存しています。修正コミットは `da376ed` です。

## まとめ

判断規則は3つです。

1. `orElse(T)` の引数式は、Optionalの中身を確認する前に評価されます。
2. Fallbackが取得処理や副作用を含むなら、`orElseGet(Supplier)` で必要時だけ実行します。
3. 戻り値の値だけでなく、不要な呼び出し回数や副作用もテストで観測します。

## 参考資料

[1]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Optional.html "Optional — Java SE 21"
[2]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/function/Supplier.html "Supplier — Java SE 21"
[3]: https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.12.4 "Java Language Specification 21, Run-Time Evaluation of Method Invocation"
