package com.careehub.compiler;

import com.careehub.compiler.config.DotenvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CompilerApplication {

	public static void main(String[] args) {
		DotenvLoader.load();
		SpringApplication.run(CompilerApplication.class, args);
	}

}
