package com.project.person.admin.controller;

import com.project.person.admin.model.Person;
import com.project.person.admin.mapper.PersonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/persons")
public class PersonController {

    @Autowired
    private PersonMapper personMapper;

    @GetMapping
    public List<Person> list() {
        return personMapper.findAll();
    }

    @GetMapping("/{id}")
    public Person get(@PathVariable Long id) {
        return personMapper.findById(id);
    }

    @PostMapping
    public Person create(@RequestBody Person person) {
        personMapper.insert(person);
        return person;
    }

    @PutMapping("/{id}")
    public Person update(@PathVariable Long id, @RequestBody Person person) {
        person.setId(id);
        personMapper.update(person);
        return personMapper.findById(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        personMapper.deleteById(id);
    }
}
