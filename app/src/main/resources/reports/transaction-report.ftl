<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>Transaktionsrapport</h2>
  <p class="lead">Detaljerad transaktionslista för perioden med både verifikations- och radtext.</p>
  <ul class="summary">
    <#list summaryLines as line>
      <li>${line}</li>
    </#list>
  </ul>
  <table>
    <thead>
      <tr>
        <#list tableHeaders as header>
          <th>${header}</th>
        </#list>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <tr>
          <#list row as cell>
            <td>${cell}</td>
          </#list>
        </tr>
      </#list>
    </tbody>
  </table>
</@layout.page>
