package main

import (
	"log"
	"os"

	"tinitalk/internal/command"
)

func main() {
	if err := command.Run(os.Stdout, os.Args[1:]...); err != nil {
		log.Fatal(err)
	}
}
