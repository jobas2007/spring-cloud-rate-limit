package com.aslearn.bookservice;


import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/books")
@Slf4j
public class BookController {
    @GetMapping
    public List<Book> getBooks() {
        log.info("Getting - Book");
        List<Book> books = new ArrayList<>();

        Book book1 = new Book();
        book1.setTitle("Title - JavaScript One");
        book1.setAuthor("Author - Fredrick S");
        books.add(book1);
        //
        Book book2 = new Book();
        book2.setTitle("Title - Python One");
        book2.setAuthor("Author - Micheal B");

        books.add(book2);
        return books;
    }


}
