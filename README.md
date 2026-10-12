
# senior-accounting-officer-registration

Backend microservice for Senior Accounting Officer registration.

The service exposes protected registration endpoints and orchestrates sign-up requests with downstream services. It orchestrates downstream requests to:

1. ETMP
1. DPS Subscription Write
1. EACD
1. email (for the confirmation email)
1. explicit audit

## Running the service

Start the service locally with:

```bash
sbt run
```

By default the service runs on port `10059`. In development, it proxies requests to `senior-accounting-officer-stubs`, which is expected on port `10061`.

## Endpoints

### sign-up

```text
POST /senior-accounting-officer-registration/sign-up
```

accepts a JSON request body and orchestrates the required work. See Bruno collection for examples.

## Tests

Run the tests with sbt.

```bash
sbt test    # unit tests
sbt it/test # integration tests
sbt lint    # formatting/linting
```

## OpenApi Schema

This repository utilises [play-swagger](https://github.com/play-swagger/play-swagger) to generate an open api specification using a code-first approach.

The schema is generated upon running `sbt run`. It can be found in `/target/swagger/swagger.json` from the root of the repository.

### Viewing the Schema

The schema is served from `http://localhost:10059/swagger`.

You can view the docs by:

* running `sm2 -start DEVHUB_PREVIEW_OPENAPI`
* accessing http://localhost:9680/api-documentation/docs/openapi/preview in a web browser
* Providing the URL `http://localhost:10059/swagger` and submitting the form

## Running tests with Bruno

Users can import the collection into Bruno and, once the required environment setup is complete, run the protected service requests directly.

### What is Bruno?

Bruno is an open-source API client used for manual exploratory testing and cross-team collaboration. It allows you to create, manage and run HTTP requests against a service directly from a collection stored in a repository.

### Pre-requisites:
* Download and install Bruno.
* Start the needed services in a terminal session with the following command:

```bash
sm2 --start SAO_ALL
```

### Running Tests in the Local environment

* Open Bruno.
* From the Bruno menu select Open Collection.
* Navigate to the bruno folder in the repository.
* Click the Open button.
* In Bruno, select the local environment from the environment dropdown.
* Select a request from the collection in the left-hand panel.
* Click Send to execute the request.
* Verify the response status and body match the expected output.
## License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
