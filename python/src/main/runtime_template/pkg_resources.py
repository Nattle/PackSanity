"""Read-only package resource queries used by tracker world registration.

This replaces the two deprecated setuptools queries needed during metadata import. Package
installation, dependency resolution, and filesystem extraction are unavailable on mobile.
"""
from importlib.resources import files


def resource_listdir(package_or_module, resource_name):
    return [entry.name for entry in files(package_or_module).joinpath(resource_name).iterdir()]


def resource_isdir(package_or_module, resource_name):
    return files(package_or_module).joinpath(resource_name).is_dir()
