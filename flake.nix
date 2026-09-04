{
  description = "Dev shell for the functional Scala Course microservice (self-contained)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";
  };

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };
    in {
      devShells.${system}.default = pkgs.mkShell {
        buildInputs = with pkgs; [
          jdk21
          sbt
          scala
          coursier
          postgresql
          docker
          docker-compose
          git
          direnv
        ];

        shellHook = ''
          export JAVA_HOME=${pkgs.jdk21}/lib/openjdk
          export PATH=$JAVA_HOME/bin:$PATH
          echo "Entering scala-course dev shell (functional Scala Course microservice)"
          echo "Scala:"
          scala -version
          echo
          echo "sbt:"
          sbt --version
          echo
          echo "Java:"
          java -version
          echo
          echo "psql:"
          psql --version
          echo
          echo "Infra: docker compose up -d  (rabbitmq for async events; postgres + legacy services live in the repo-root compose)"
        '';
      };
    };
}
