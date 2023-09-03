import { useEffect, useState } from "react";
import { Button } from "antd";
import { ShoppingCartOutlined } from "@ant-design/icons";
import axios from "axios";
import {
  SpanStatusCode,
  context,
  createTraceState,
  propagation,
  trace,
} from "@opentelemetry/api";

import Book from "../types/Book";
import CartItem from "../types/CartItem";
import { useCart } from "../hooks/useCart";
import CheckoutButton from "../components/CheckoutButton";
import "./BookList.css";
import tracer from "../utils/otel/tracer";

export const BookList: React.FC = () => {
  // const history = useNavigate();
  const [books, setBooks] = useState<Book[]>([]);
  const { cart, addBookToCart, increaseQuantity, decreaseQuantity } = useCart();

  useEffect(() => {
    // 调用获取书籍列表的接口, 并将 span context 注入到 headers 中
    const fetchBooks = async () => {
      const span = tracer.startSpan("fetchBooks");

      // 获取当前 span 的 span context（包含 traceId、spanId、traceFlags、traceState）
      const spanContext = span.spanContext();
      spanContext.traceState = createTraceState(
        "rojo=00f067aa0ba902b7,congo=t61rcWkgMzE"
      );

      const headers = {};

      context.with(trace.setSpan(context.active(), span), () => {
        propagation.inject(context.active(), headers);
        // console.log(22, propagation.getActiveBaggage());
        console.log(1, headers);
        axios
          .get("/api/catalog/books", { headers: headers })
          .then((res) => {
            setBooks(res.data as Book[]);
            span.addEvent("fetchBooks success");
            span.setStatus({ code: SpanStatusCode.OK });
          })
          .catch((err) => {
            console.log(err);
            span.recordException(err);
            span.setStatus({
              code: SpanStatusCode.ERROR,
              message: err.message,
            });
          })
          .finally(() => {
            span.end();
          });
      });
    };
    fetchBooks();
  }, []);

  const addToCart = (book: Book) => {
    addBookToCart({
      // 将 book 转换成 cartItem
      id: book.id,
      title: book.title,
      cover_url: book.cover_url,
      price: book.price,
    } as CartItem);
  };

  return (
    <ul className="book-list">
      <CheckoutButton />
      {books.map((book) => {
        const bookInCart = cart.find((item) => item.id === book.id);
        return (
          <li className="book-item" key={book.id}>
            <div className="inner">
              <div className="cover shadow-cover">
                <span className="cover-label"></span>
                <img src={book.cover_url} alt={book.title} />
              </div>

              <div className="info">
                <h3 className="title">
                  <span className="title-text">{book.title}</span>
                </h3>
                <div className="author">
                  <span className="">{book.author}</span>
                </div>
                <div className="intro">
                  <span className="intro-text">{book.description}</span>
                </div>
                <div className="actions">
                  <div className="actions-left">
                    <span className="sale">
                      <span className="price-tag">
                        <span className="rmb-tag">￥</span>
                        <span className="discount-price">
                          {(book.price / 100).toFixed(2)}
                        </span>
                      </span>
                    </span>
                  </div>
                  <div className="actions-right">
                    {bookInCart ? (
                      <>
                        <Button
                          size="small"
                          onClick={() => decreaseQuantity(book.id)}
                        >
                          -
                        </Button>
                        <span className="quantity-tag">
                          {bookInCart.quantity}
                        </span>
                        <Button
                          size="small"
                          onClick={() => increaseQuantity(book.id)}
                        >
                          +
                        </Button>
                      </>
                    ) : (
                      <Button
                        icon={<ShoppingCartOutlined />}
                        onClick={() => addToCart(book)}
                      >
                        加入购物车
                      </Button>
                    )}
                  </div>
                </div>
              </div>
            </div>
          </li>
        );
      })}
    </ul>
  );
};
