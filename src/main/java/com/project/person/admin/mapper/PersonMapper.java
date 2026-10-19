package com.project.person.admin.mapper;

import com.project.person.admin.model.Person;

import java.util.List;

public interface PersonMapper {

    List<Person> findAll();

    Person findById(Long id);

    int insert(Person person);

    int update(Person person);

    int deleteById(Long id);
}
