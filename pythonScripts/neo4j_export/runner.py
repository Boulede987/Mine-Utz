import sys

from .config import NEO4J_URI, NEO4J_USER, NEO4J_PASSWORD
from .exporter import export

try:
    from neo4j import GraphDatabase
except ImportError:
    print("ERROR: neo4j driver not installed.")
    print("       Run: pip install neo4j")
    sys.exit(1)


def connect_driver():
    print(f"Connecting to Neo4j at {NEO4J_URI}...")
    try:
        driver = GraphDatabase.driver(NEO4J_URI, auth=(NEO4J_USER, NEO4J_PASSWORD))
        driver.verify_connectivity()
        print("Connected.\n")
        return driver
    except Exception as error:
        print(f"Connection failed: {error}")
        print("Ensure Neo4j is running and NEO4J_PASSWORD is correct.")
        sys.exit(1)


def run() -> None:
    driver = connect_driver()
    try:
        export(driver)
    finally:
        driver.close()
